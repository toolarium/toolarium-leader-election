/*
 * LeaderElectionDAO.java
 *
 * Copyright by toolarium, all rights reserved.
 */
package com.github.toolarium.leader.election.impl.database.dao;

import com.github.toolarium.leader.election.dto.db.DatabaseLeaderElectionConfiguration;
import com.github.toolarium.leader.election.dto.db.DatabaseLeaderElectionConfiguration.LeaderElectionDatabaseStructure;
import com.github.toolarium.leader.election.dto.db.JDBCLeaderElectionDatabaseStructure;
import com.github.toolarium.leader.election.dto.db.MariaDBLeaderElectionDatabaseStructure;
import com.github.toolarium.leader.election.dto.db.MySQLLeaderElectionDatabaseStructure;
import com.github.toolarium.leader.election.dto.db.OracleLeaderElectionDatabaseStructure;
import com.github.toolarium.leader.election.dto.db.SQLServerLeaderElectionDatabaseStructure;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.SQLIntegrityConstraintViolationException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


/**
 * Leader election database access object.
 * Each public method borrows a connection from the DataSource, uses it, and returns it
 * immediately; connections are never held between calls.
 *
 * @author patrick
 */
public class LeaderElectionDAO {
    private static final Logger LOG = LoggerFactory.getLogger(LeaderElectionDAO.class);
    private final DatabaseLeaderElectionConfiguration leaderElectionConfiguration;
    private volatile LeaderElectionDatabaseStructure leaderElectionDatabaseStructure;
    private final int queryTimeoutSeconds;


    /**
     * Constructor for LeaderElectionDAO
     *
     * @param leaderElectionConfiguration the leader election database configuration
     */
    public LeaderElectionDAO(DatabaseLeaderElectionConfiguration leaderElectionConfiguration) {
        this.leaderElectionConfiguration = leaderElectionConfiguration;
        this.leaderElectionDatabaseStructure = leaderElectionConfiguration.getLeaderElectionDatabaseStructure();
        // Derive the per-statement JDBC query timeout from renewDeadline so the whole tick
        // (up to 3 sequential statements) fits within the renew deadline.  Using retryPeriod alone
        // would allow 3 * retryPeriod per tick, which can exceed renewDeadline and create a
        // split-brain window.  A floor of 3 000 ms ensures queryTimeoutSeconds >= 1: below that
        // each statement would be given a 0 s timeout, which most drivers treat as "no timeout".
        long renewMillis = leaderElectionConfiguration.getRenewDeadline().toMillis();
        if (renewMillis < 3_000) {
            LOG.info("renewDeadline [{}ms] is below 3 000 ms; the per-statement query timeout is "
                    + "capped at 1 s. A tick with 3 sequential statements may therefore exceed the "
                    + "renew deadline. Configure renewDeadline >= 3 s to avoid this.", renewMillis);
            renewMillis = 3_000;
        }
        this.queryTimeoutSeconds = Math.max(1, (int) (renewMillis / 3 / 1000));
    }


    /**
     * Initialize: create the leader-election table if it does not already exist.
     * Two nodes starting simultaneously are handled: if CREATE TABLE fails because another
     * node created the table concurrently, we re-check and continue normally.
     *
     * @throws SQLException In case of a database exception
     */
    public void init() throws SQLException {
        try (Connection conn = leaderElectionConfiguration.getDataSource().getConnection()) {
            ensureStructure(conn);

            if (!existDatabaseTable(conn)) {
                if (!leaderElectionConfiguration.isAllowDdl()) {
                    throw new SQLException("Leader election table '" + leaderElectionDatabaseStructure.getTableName()
                                            + "' does not exist and DDL is disabled (allowDdl=false). "
                                            + "Create the table manually or call setAllowDdl(true) on the configuration. "
                                            + "Expected DDL: " + leaderElectionDatabaseStructure.createSQLTable());
                }
                if (LOG.isDebugEnabled()) {
                    LOG.debug("Creating leader election table {}...", leaderElectionDatabaseStructure.getTableName());
                }
                try {
                    try (Statement stmt = conn.createStatement()) {
                        stmt.execute(leaderElectionDatabaseStructure.createSQLTable());
                    }
                    String indexSql = leaderElectionDatabaseStructure.createSQLIndex();
                    if (indexSql != null) {
                        try (Statement idxStmt = conn.createStatement()) {
                            idxStmt.execute(indexSql);
                        }
                    }
                } catch (SQLException e) {
                    // Concurrent creation: another node may have won the race; re-check before failing.
                    if (!existDatabaseTable(conn)) {
                        throw e;
                    }
                    if (LOG.isDebugEnabled()) {
                        LOG.debug("Table {} was created concurrently by another node.", leaderElectionDatabaseStructure.getTableName());
                    }
                }
            } else {
                if (LOG.isDebugEnabled()) {
                    LOG.debug("Leader election table {} exists.", leaderElectionDatabaseStructure.getTableName());
                }
            }

            validateAndMigrateSchema(conn);
        }
    }


    /**
     * No-op: connections are borrowed per-operation from the DataSource and are not held.
     */
    public void close() {
        // Connections are borrowed per-operation; nothing to release here.
    }


    /**
     * Ensure the database structure is initialized, detecting it from the connection if null.
     *
     * @param conn an active database connection
     * @throws SQLException in case of a database error
     */
    private void ensureStructure(Connection conn) throws SQLException {
        // Fast path: already initialised (volatile read; avoids lock acquisition on every tick).
        if (leaderElectionDatabaseStructure != null) {
            return;
        }
        // Slow path: first call or concurrent initialisation.  Double-checked locking is safe
        // here because leaderElectionDatabaseStructure is volatile (TS-4).
        synchronized (this) {
            if (leaderElectionDatabaseStructure == null) {
                String productName = conn.getMetaData().getDatabaseProductName();
                leaderElectionDatabaseStructure = detectDatabaseStructure(productName);
                leaderElectionConfiguration.setLeaderElectionDatabaseStructure(leaderElectionDatabaseStructure);
                LOG.info("Auto-detected database structure: {} for '{}'.", leaderElectionDatabaseStructure.getClass().getSimpleName(), productName);
            }
        }
    }


    /**
     * Select the appropriate database structure based on the JDBC product name.
     * MariaDB is checked before MySQL because some MariaDB connectors may include
     * "MySQL" in the product name string.
     *
     * @param productName the value returned by {@link DatabaseMetaData#getDatabaseProductName()}
     * @return the matching {@link LeaderElectionDatabaseStructure}
     */
    private LeaderElectionDatabaseStructure detectDatabaseStructure(String productName) {
        String p = productName.toLowerCase(Locale.ROOT);
        if (p.contains("oracle")) {
            return new OracleLeaderElectionDatabaseStructure();
        } else if (p.contains("mariadb")) {
            return new MariaDBLeaderElectionDatabaseStructure();
        } else if (p.contains("mysql")) {
            return new MySQLLeaderElectionDatabaseStructure();
        } else if (p.contains("sql server")) {
            return new SQLServerLeaderElectionDatabaseStructure();
        }
        return new JDBCLeaderElectionDatabaseStructure();
    }


    /**
     * Insert a new leader record.
     *
     * @param leaderElectorRecord the leader record
     * @return number of inserted rows, or -1 if a unique-constraint violation was detected (lost race)
     * @throws SQLException In case of a real database error
     */
    public int insertLeader(LeaderElectorRecord leaderElectorRecord) throws SQLException {
        try (Connection conn = leaderElectionConfiguration.getDataSource().getConnection();
             PreparedStatement stmt = conn.prepareStatement(leaderElectionDatabaseStructure.createSQLInsertLeader())) {
            stmt.setQueryTimeout(queryTimeoutSeconds);
            JDBCUtil.getInstance().setValues(stmt, new Object[] {
                leaderElectorRecord.getId(), leaderElectorRecord.getName(),
                leaderElectorRecord.getInstance(), leaderElectorRecord.getUser(),
                leaderElectorRecord.getTimestamp()
            });
            int rows = stmt.executeUpdate();
            commitIfNeeded(conn);
            return rows;
        } catch (SQLIntegrityConstraintViolationException uc) {
            return -1;
        } catch (SQLException e) {
            // pgjdbc and some other drivers do not use the JDBC 4 subclass; treat SQLState class "23" as a lost race.
            if (e.getSQLState() != null && e.getSQLState().startsWith("23")) {
                return -1;
            }
            throw e;
        }
    }


    /**
     * Delete a leader by name.
     *
     * @param name the name
     * @return the number of deleted records
     * @throws SQLException In case of a database error
     */
    public int deleteLeaderByName(String name) throws SQLException {
        try (Connection conn = leaderElectionConfiguration.getDataSource().getConnection();
             PreparedStatement stmt = conn.prepareStatement(leaderElectionDatabaseStructure.createSQLDeleteLeaderByName())) {
            stmt.setQueryTimeout(queryTimeoutSeconds);
            JDBCUtil.getInstance().setValues(stmt, new Object[] {name});
            int rows = stmt.executeUpdate();
            commitIfNeeded(conn);
            return rows;
        }
    }


    /**
     * Delete the leader record for the given stable instance id, regardless of election group name.
     * Used by the administration API to force-release a remote leader in a single round-trip.
     *
     * @param instanceId the stable elector instance id
     * @return the number of deleted records (0 if not found, 1 if found and deleted)
     * @throws SQLException In case of a database error
     */
    public int deleteLeaderByInstance(String instanceId) throws SQLException {
        try (Connection conn = leaderElectionConfiguration.getDataSource().getConnection();
             PreparedStatement stmt = conn.prepareStatement(leaderElectionDatabaseStructure.createSQLDeleteLeaderByInstance())) {
            stmt.setQueryTimeout(queryTimeoutSeconds);
            JDBCUtil.getInstance().setValues(stmt, new Object[] {instanceId});
            int rows = stmt.executeUpdate();
            commitIfNeeded(conn);
            return rows;
        }
    }


    /**
     * Delete a leader by name and instance. Uses the stable elector instance identifier
     * so the delete succeeds even when a concurrent scheduler tick has already renewed
     * the per-renewal {@code id} column.
     *
     * @param name the name
     * @param instanceId the stable elector instance id
     * @return the number of deleted records
     * @throws SQLException In case of a database error
     */
    public int deleteLeaderByNameAndInstance(String name, String instanceId) throws SQLException {
        try (Connection conn = leaderElectionConfiguration.getDataSource().getConnection();
            PreparedStatement stmt = conn.prepareStatement(leaderElectionDatabaseStructure.createSQLDeleteLeaderByNameAndInstance())) {
            stmt.setQueryTimeout(queryTimeoutSeconds);
            JDBCUtil.getInstance().setValues(stmt, new Object[] {name, instanceId});
            int rows = stmt.executeUpdate();
            commitIfNeeded(conn);
            return rows;
        }
    }


    /**
     * Select the current leader for the given election group name.
     *
     * @param name the election group name
     * @return the current leader record, or null if no leader exists
     * @throws SQLException In case of a database error
     */
    public LeaderElectorRecord selectLeaderByName(String name) throws SQLException {
        try (Connection conn = leaderElectionConfiguration.getDataSource().getConnection();
             PreparedStatement stmt = conn.prepareStatement(leaderElectionDatabaseStructure.createSQLSelectLeaderByName())) {
            stmt.setQueryTimeout(queryTimeoutSeconds);
            JDBCUtil.getInstance().setValues(stmt, new Object[] {name});
            try (ResultSet rs = stmt.executeQuery()) {
                if (rs.next()) {
                    Object[] columns = JDBCUtil.getInstance().getValues(rs);
                    // columns[4] is the lease_ts epoch-millis; Oracle returns NUMBER(19) as BigDecimal
                    // while most other drivers return Long; use Number.longValue() to handle both.
                    long leaseTs;
                    if (columns[4] instanceof Number) {
                        leaseTs = ((Number) columns[4]).longValue();
                    } else {
                        leaseTs = Long.parseLong(columns[4].toString());
                    }
                    // columns[5] is force_release_deadline - nullable; 0 means not set.
                    long forceReleaseDeadline = 0L;
                    if (columns.length > 5 && columns[5] != null) {
                        if (columns[5] instanceof Number) {
                            forceReleaseDeadline = ((Number) columns[5]).longValue();
                        } else {
                            forceReleaseDeadline = Long.parseLong(columns[5].toString());
                        }
                    }
                    LeaderElectorRecord record = new LeaderElectorRecord((String) columns[0], (String) columns[1], (String) columns[2], (String) columns[3], leaseTs, forceReleaseDeadline);
                    if (LOG.isDebugEnabled()) {
                        LOG.debug("Leader found: id={}, name={}, instance={}", record.getId(), record.getName(), record.getInstance());
                    }
                    return record;
                }
            }
        }
        return null;
    }


    /**
     * Select all stored leader election records (one row per election group).
     * Each row represents the current leader for that election group, from any node.
     *
     * @return list of all leader records; never {@code null}, may be empty
     * @throws SQLException In case of a database error
     */
    public List<LeaderElectorRecord> selectAll() throws SQLException {
        return selectAll(0);
    }


    /**
     * Select at most {@code maxRows} stored leader election records.
     * When {@code maxRows} is positive, {@link Statement#setMaxRows(int)} is set so the
     * database engine stops scanning after that many rows; no full table scan for an oversized
     * table.  Pass {@code 0} for no limit (equivalent to {@link #selectAll()}).
     *
     * @param maxRows maximum number of rows to return; {@code 0} means no limit
     * @return list of leader records; never {@code null}, may be empty
     * @throws SQLException In case of a database error
     */
    public List<LeaderElectorRecord> selectAll(int maxRows) throws SQLException {
        List<LeaderElectorRecord> result = new ArrayList<>();
        try (Connection conn = leaderElectionConfiguration.getDataSource().getConnection()) {
            ensureStructure(conn);
            try (Statement stmt = conn.createStatement()) {
                stmt.setQueryTimeout(queryTimeoutSeconds);
                if (maxRows > 0) {
                    stmt.setMaxRows(maxRows);
                }
                try (ResultSet rs = stmt.executeQuery(leaderElectionDatabaseStructure.createSQLSelectAll())) {
                    while (rs.next()) {
                        Object[] columns = JDBCUtil.getInstance().getValues(rs);
                        long leaseTs;
                        if (columns[4] instanceof Number) {
                            leaseTs = ((Number) columns[4]).longValue();
                        } else {
                            leaseTs = Long.parseLong(columns[4].toString());
                        }
                        long forceReleaseDeadline = 0L;
                        if (columns.length > 5 && columns[5] != null) {
                            if (columns[5] instanceof Number) {
                                forceReleaseDeadline = ((Number) columns[5]).longValue();
                            } else {
                                forceReleaseDeadline = Long.parseLong(columns[5].toString());
                            }
                        }
                        result.add(new LeaderElectorRecord(
                            (String) columns[0], (String) columns[1],
                            (String) columns[2], (String) columns[3], leaseTs, forceReleaseDeadline));
                    }
                }
            }
        }
        return result;
    }


    /**
     * Atomically renew the leader's lease. Only succeeds when name and instance still match.
     *
     * @param name the election group name
     * @param instanceId the elector's stable instance id
     * @param newId the new record id (UUID)
     * @param timestamp the new lease timestamp as epoch milliseconds
     * @return number of updated rows (1 on success, 0 if we are no longer the leader)
     * @throws SQLException In case of a database error
     */
    public int updateLeaderTimestamp(String name, String instanceId, String newId, long timestamp) throws SQLException {
        try (Connection conn = leaderElectionConfiguration.getDataSource().getConnection();
             PreparedStatement stmt = conn.prepareStatement(leaderElectionDatabaseStructure.createSQLUpdateLeaderTimestamp())) {
            stmt.setQueryTimeout(queryTimeoutSeconds);
            JDBCUtil.getInstance().setValues(stmt, new Object[] {newId, timestamp, name, instanceId});
            int rows = stmt.executeUpdate();
            commitIfNeeded(conn);
            return rows;
        }
    }


    /**
     * Atomically steal an expired or force-released leadership by updating the row in a single statement.
     * The update only succeeds when the row's id still matches {@code currentLeaderId} AND either:
     * <ul>
     *   <li>the lease has expired ({@code lease_ts < expiryThreshold}), OR</li>
     *   <li>an admin force-release deadline has elapsed ({@code force_release_deadline IS NOT NULL AND force_release_deadline <= dbNow}).</li>
     * </ul>
     * The stolen row has {@code force_release_deadline} reset to {@code NULL}.
     * Returns 0 when another node won the race (no matching row updated).
     *
     * @param name the election group name
     * @param currentLeaderId the id of the row we expect to steal
     * @param expiryThreshold epoch-millis threshold: the stored lease_ts must be strictly less than this value
     * @param dbNow current DB-clock epoch millis for the force-release deadline comparison
     * @param newRecord the challenger's new leader record
     * @return number of updated rows (1 on success, 0 if the row no longer matches)
     * @throws SQLException In case of a database error
     */
    public int stealLeadership(String name, String currentLeaderId, long expiryThreshold, long dbNow, LeaderElectorRecord newRecord) throws SQLException {
        try (Connection conn = leaderElectionConfiguration.getDataSource().getConnection();
             PreparedStatement stmt = conn.prepareStatement(leaderElectionDatabaseStructure.createSQLStealLeadership())) {
            stmt.setQueryTimeout(queryTimeoutSeconds);
            JDBCUtil.getInstance().setValues(stmt, new Object[] {
                newRecord.getId(), newRecord.getInstance(), newRecord.getUser(),
                newRecord.getTimestamp(), name, currentLeaderId, expiryThreshold, dbNow
            });
            int rows = stmt.executeUpdate();
            commitIfNeeded(conn);
            return rows;
        }
    }


    /**
     * Set the admin force-release deadline for the leader identified by the given stable instance id.
     * After the deadline elapses, no node (including the current holder) may hold or acquire
     * leadership until a new election completes normally.
     *
     * @param instanceId the stable elector instance id of the leader to force-release
     * @param deadlineMillis the force-release deadline as epoch milliseconds
     * @return number of updated rows (1 if found, 0 if not found)
     * @throws SQLException In case of a database error
     */
    public int setForceReleaseDeadline(String instanceId, long deadlineMillis) throws SQLException {
        try (Connection conn = leaderElectionConfiguration.getDataSource().getConnection();
             PreparedStatement stmt = conn.prepareStatement(leaderElectionDatabaseStructure.createSQLSetForceReleaseDeadline())) {
            stmt.setQueryTimeout(queryTimeoutSeconds);
            JDBCUtil.getInstance().setValues(stmt, new Object[] {deadlineMillis, instanceId});
            int rows = stmt.executeUpdate();
            commitIfNeeded(conn);
            return rows;
        }
    }


    /**
     * Query the database server's current time and return it as epoch milliseconds.
     * Throws {@link SQLException} if the connection or query fails - callers must handle
     * this as a tick failure rather than falling back to the local clock, which would
     * reintroduce the node-clock-skew problem this method is designed to eliminate.
     *
     * @return the database server's current time as epoch milliseconds
     * @throws SQLException if the connection or query fails
     */
    public long getDatabaseCurrentTimeMillis() throws SQLException {
        try (Connection conn = leaderElectionConfiguration.getDataSource().getConnection()) {
            ensureStructure(conn);
            try (Statement stmt = conn.createStatement()) {
                stmt.setQueryTimeout(queryTimeoutSeconds);
                try (ResultSet rs = stmt.executeQuery(leaderElectionDatabaseStructure.createSQLSelectCurrentTimestampMillis())) {
                    if (rs.next()) {
                        Timestamp ts = rs.getTimestamp(1);
                        if (ts != null) {
                            return ts.getTime();
                        }
                    }
                }
            }
        }
        return System.currentTimeMillis();
    }


    /**
     * Auto-migrate the legacy {@code timestamp} column to {@code lease_ts} if it exists,
     * then validate that all expected columns are present in the table.
     * Throws a descriptive {@link SQLException} if the schema does not match so the
     * operator gets a clear error instead of cryptic column-not-found failures later.
     *
     * @param conn an active database connection
     * @throws SQLException if migration fails or required columns are missing
     */
    protected void validateAndMigrateSchema(Connection conn) throws SQLException {
        boolean allowDdl = leaderElectionConfiguration.isAllowDdl();
        Set<String> columns = getExistingColumns(conn);

        // Auto-migrate: rename legacy 'timestamp' column to 'lease_ts'
        if (columns.contains("timestamp") && !columns.contains("lease_ts")) {
            String tableName = leaderElectionDatabaseStructure.getTableName();
            if (!allowDdl) {
                throw new SQLException("Schema migration required for table '" + tableName
                                        + "': column 'timestamp' must be renamed to 'lease_ts', but DDL is disabled "
                                        + "(allowDdl=false). Apply the migration manually: "
                                        + leaderElectionDatabaseStructure.createSQLRenameTimestampColumn());
            }
            LOG.info("Migrating schema: renaming column 'timestamp' to 'lease_ts' in table {}...", tableName);
            try {
                try (Statement stmt = conn.createStatement()) {
                    stmt.execute(leaderElectionDatabaseStructure.createSQLRenameTimestampColumn());
                }
                LOG.info("Schema migration of table {} completed successfully.", tableName);
                columns.remove("timestamp");
                columns.add("lease_ts");
            } catch (SQLException e) {
                // Concurrent migration: another node may have renamed the column simultaneously; re-check before failing.
                columns = getExistingColumns(conn);
                if (!columns.contains("lease_ts")) {
                    throw e;
                }
                if (LOG.isDebugEnabled()) {
                    LOG.debug("Column 'timestamp' in table {} was renamed concurrently by another node.", tableName);
                }
            }
        }

        // Auto-migrate: add force_release_deadline column if missing (schema v1 → v2)
        if (!columns.contains("force_release_deadline")) {
            String tableName = leaderElectionDatabaseStructure.getTableName();
            if (!allowDdl) {
                throw new SQLException("Schema migration required for table '" + tableName
                                        + "': column 'force_release_deadline' is missing, but DDL is disabled "
                                        + "(allowDdl=false). Apply the migration manually: "
                                        + leaderElectionDatabaseStructure.createSQLAddForceReleaseDeadlineColumn());
            }
            LOG.info("Migrating schema: adding column 'force_release_deadline' to table {}...", tableName);
            try {
                try (Statement stmt = conn.createStatement()) {
                    stmt.execute(leaderElectionDatabaseStructure.createSQLAddForceReleaseDeadlineColumn());
                }
                LOG.info("Schema migration of table {} completed (force_release_deadline added).", tableName);
                columns.add("force_release_deadline");
            } catch (SQLException e) {
                // Concurrent migration: another node may have added the column simultaneously; re-check before failing.
                columns = getExistingColumns(conn);
                if (!columns.contains("force_release_deadline")) {
                    throw e;
                }
                if (LOG.isDebugEnabled()) {
                    LOG.debug("Column 'force_release_deadline' in table {} was added concurrently by another node.", tableName);
                }
            }
        }

        // Validate that all expected columns are present
        Set<String> expected = leaderElectionDatabaseStructure.getExpectedColumnNames();
        Set<String> missing = new LinkedHashSet<>(expected);
        missing.removeAll(columns);
        if (!missing.isEmpty()) {
            throw new SQLException(
                "Schema mismatch for table '" + leaderElectionDatabaseStructure.getTableName()
                + "': missing columns " + missing
                + ". Expected: " + expected + ", found: " + columns
                + ". The table schema does not match the current version (schema v"
                + DatabaseLeaderElectionConfiguration.SCHEMA_VERSION + ")."
                + " Please update or drop the table and let it be recreated.");
        }
        LOG.info("Schema validation passed for table '{}' (schema v{}).", leaderElectionDatabaseStructure.getTableName(), DatabaseLeaderElectionConfiguration.SCHEMA_VERSION);
    }


    /**
     * Return the set of column names (lowercased) that exist in the leader-election table.
     * Tries the exact table name first, then uppercase (Oracle), then lowercase (PostgreSQL).
     *
     * @param conn an active database connection
     * @return lowercased column names, empty set if the table cannot be found in the catalog
     * @throws SQLException in case of a database error
     */
    protected Set<String> getExistingColumns(Connection conn) throws SQLException {
        String tableName = leaderElectionDatabaseStructure.getTableName();
        DatabaseMetaData metaData = conn.getMetaData();
        String schema = null;
        try {
            schema = conn.getSchema();
        } catch (Exception e) {
            if (LOG.isDebugEnabled()) {
                LOG.debug("Could not retrieve schema for column lookup: {}", e.getMessage());
            }
        }

        for (String nameToTry : new String[]{tableName, tableName.toUpperCase(), tableName.toLowerCase()}) {
            Set<String> columns = new LinkedHashSet<>();
            try (ResultSet rs = metaData.getColumns(null, schema, nameToTry, null)) {
                while (rs.next()) {
                    columns.add(rs.getString("COLUMN_NAME").toLowerCase());
                }
            }
            if (!columns.isEmpty()) {
                return columns;
            }
        }
        return new LinkedHashSet<>();
    }


    /**
     * Commit the current connection's transaction if auto-commit is disabled.
     * Connection pools configured without auto-commit (or connections used inside a non-managed
     * transaction) would otherwise roll back every DML write when the connection is returned to
     * the pool, silently losing the lease write while the local node still considers itself leader.
     *
     * @param conn an active database connection
     * @throws SQLException if the commit fails
     */
    private void commitIfNeeded(Connection conn) throws SQLException {
        // Read getAutoCommit() from the connection on every call: different callers (election
        // scheduler thread, forceRelease, ILeaderLease.tryExtend inside a JTA transaction) may
        // receive connections with different auto-commit settings from the pool.  Caching the flag
        // from the first connection and reusing it for others would either skip a required commit
        // (rolled-back lease renewal that the elector incorrectly counts as successful) or call
        // commit() on an auto-commit connection (rejected by pgjdbc).  getAutoCommit() is answered
        // from the connection state by all common drivers without a round-trip, so the overhead is
        // negligible.
        if (!conn.getAutoCommit()) {
            conn.commit();
        }
    }


    /**
     * Check whether the leader-election table exists.
     * Tries the exact table name first, then the uppercase form (Oracle stores unquoted
     * identifiers in uppercase).
     *
     * @param conn an active database connection
     * @return true if the table exists
     * @throws SQLException In case of a database error
     */
    protected boolean existDatabaseTable(Connection conn) throws SQLException {
        String tableName = leaderElectionDatabaseStructure.getTableName();
        DatabaseMetaData metaData = conn.getMetaData();
        String schema = null;
        try {
            schema = conn.getSchema();
        } catch (Exception e) {
            if (LOG.isDebugEnabled()) {
                LOG.debug("Could not retrieve schema, searching all schemas: {}", e.getMessage());
            }
        }

        // Try exact case, then uppercase (Oracle), then lowercase (PostgreSQL); databases differ
        // in how they fold unquoted identifiers in their metadata catalog.
        for (String nameToTry : new String[]{tableName, tableName.toUpperCase(), tableName.toLowerCase()}) {
            try (ResultSet rs = metaData.getTables(null, schema, nameToTry, new String[]{"TABLE"})) {
                if (rs.next()) {
                    if (LOG.isDebugEnabled()) {
                        LOG.debug("Table [{}] found.", tableName);
                    }
                    return true;
                }
            }
        }
        if (LOG.isDebugEnabled()) {
            LOG.debug("Table [{}] not found.", tableName);
        }
        return false;
    }
}
