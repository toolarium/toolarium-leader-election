/*
 * LeaderElectionDAO.java
 *
 * Copyright by toolarium, all rights reserved.
 */
package com.github.toolarium.leader.election.impl.database.dao;

import com.github.toolarium.leader.election.dto.db.DatabaseLeaderElectionConfiguration;
import com.github.toolarium.leader.election.dto.db.DatabaseLeaderElectionConfiguration.LeaderElectionDatabaseStructure;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.SQLIntegrityConstraintViolationException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.util.LinkedHashSet;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


/**
 * Leader election database access object.
 * Each public method borrows a connection from the DataSource, uses it, and returns it
 * immediately — connections are never held between calls.
 *
 * @author patrick
 */
public class LeaderElectionDAO {
    private static final Logger LOG = LoggerFactory.getLogger(LeaderElectionDAO.class);
    private final DatabaseLeaderElectionConfiguration leaderElectionConfiguration;
    private final LeaderElectionDatabaseStructure leaderElectionDatabaseStructure;


    /**
     * Constructor for LeaderElectionDAO
     *
     * @param leaderElectionConfiguration the leader election database configuration
     */
    public LeaderElectionDAO(DatabaseLeaderElectionConfiguration leaderElectionConfiguration) {
        this.leaderElectionConfiguration = leaderElectionConfiguration;
        this.leaderElectionDatabaseStructure = leaderElectionConfiguration.getLeaderElectionDatabaseStructure();
    }


    /**
     * Initialize — create the leader-election table if it does not already exist.
     * Two nodes starting simultaneously are handled: if CREATE TABLE fails because another
     * node created the table concurrently, we re-check and continue normally.
     *
     * @throws SQLException In case of a database exception
     */
    public void init() throws SQLException {
        try (Connection conn = leaderElectionConfiguration.getDataSource().getConnection()) {
            if (!existDatabaseTable(conn)) {
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
                    // Concurrent creation: another node may have won the race — re-check before failing.
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
     * Insert a new leader record.
     *
     * @param leaderElectorRecord the leader record
     * @return number of inserted rows, or -1 if a unique-constraint violation was detected (lost race)
     * @throws SQLException In case of a real database error
     */
    public int insertLeader(LeaderElectorRecord leaderElectorRecord) throws SQLException {
        try (Connection conn = leaderElectionConfiguration.getDataSource().getConnection();
             PreparedStatement stmt = conn.prepareStatement(leaderElectionDatabaseStructure.createSQLInsertLeader())) {
            JDBCUtil.getInstance().setValues(stmt, new Object[] {
                leaderElectorRecord.getId(), leaderElectorRecord.getName(),
                leaderElectorRecord.getInstance(), leaderElectorRecord.getUser(),
                leaderElectorRecord.getTimestamp()
            });
            return stmt.executeUpdate();
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
            JDBCUtil.getInstance().setValues(stmt, new Object[] {name});
            return stmt.executeUpdate();
        }
    }


    /**
     * Delete a leader by name and id.
     *
     * @param name the name
     * @param id the id
     * @return the number of deleted records
     * @throws SQLException In case of a database error
     */
    public int deleteLeaderByNameAndId(String name, String id) throws SQLException {
        try (Connection conn = leaderElectionConfiguration.getDataSource().getConnection();
             PreparedStatement stmt = conn.prepareStatement(leaderElectionDatabaseStructure.createSQLDeleteLeaderByNameAndId())) {
            JDBCUtil.getInstance().setValues(stmt, new Object[] {name, id});
            return stmt.executeUpdate();
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
            JDBCUtil.getInstance().setValues(stmt, new Object[] {name});
            try (ResultSet rs = stmt.executeQuery()) {
                if (rs.next()) {
                    Object[] columns = JDBCUtil.getInstance().getValues(rs);
                    // columns[4] is the lease_ts epoch-millis; Oracle returns NUMBER(19) as BigDecimal
                    // while most other drivers return Long — use Number.longValue() to handle both.
                    long leaseTs;
                    if (columns[4] instanceof Number) {
                        leaseTs = ((Number) columns[4]).longValue();
                    } else {
                        leaseTs = Long.parseLong(columns[4].toString());
                    }
                    LeaderElectorRecord record = new LeaderElectorRecord((String) columns[0], (String) columns[1], (String) columns[2], (String) columns[3], leaseTs);
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
            JDBCUtil.getInstance().setValues(stmt, new Object[] {newId, timestamp, name, instanceId});
            return stmt.executeUpdate();
        }
    }


    /**
     * Atomically steal an expired leadership by updating the row in a single statement.
     * The update only succeeds when the row's id still matches {@code currentLeaderId} AND
     * {@code lease_ts} is older than {@code expiryThreshold} (i.e. the lease has expired).
     * Returns 0 when another node won the race (no matching row updated).
     *
     * @param name the election group name
     * @param currentLeaderId the id of the row we expect to steal
     * @param expiryThreshold epoch-millis threshold: the stored lease_ts must be strictly less than this value
     * @param newRecord the challenger's new leader record
     * @return number of updated rows (1 on success, 0 if the row no longer matches)
     * @throws SQLException In case of a database error
     */
    public int stealLeadership(String name, String currentLeaderId, long expiryThreshold, LeaderElectorRecord newRecord) throws SQLException {
        try (Connection conn = leaderElectionConfiguration.getDataSource().getConnection();
             PreparedStatement stmt = conn.prepareStatement(leaderElectionDatabaseStructure.createSQLStealLeadership())) {
            JDBCUtil.getInstance().setValues(stmt, new Object[] {
                newRecord.getId(), newRecord.getInstance(), newRecord.getUser(),
                newRecord.getTimestamp(), name, currentLeaderId, expiryThreshold
            });
            return stmt.executeUpdate();
        }
    }


    /**
     * Query the database server's current time and return it as epoch milliseconds.
     * Falls back to {@code System.currentTimeMillis()} if the query fails.
     *
     * @return the database server's current time as epoch milliseconds
     * @throws SQLException In case of a database error
     */
    public long getDatabaseCurrentTimeMillis() throws SQLException {
        try (Connection conn = leaderElectionConfiguration.getDataSource().getConnection();
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery(leaderElectionDatabaseStructure.createSQLSelectCurrentTimestampMillis())) {
            if (rs.next()) {
                Timestamp ts = rs.getTimestamp(1);
                if (ts != null) {
                    return ts.getTime();
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
        Set<String> columns = getExistingColumns(conn);

        // Auto-migrate: rename legacy 'timestamp' column to 'lease_ts'
        if (columns.contains("timestamp") && !columns.contains("lease_ts")) {
            String tableName = leaderElectionDatabaseStructure.getTableName();
            LOG.info("Migrating schema: renaming column 'timestamp' to 'lease_ts' in table {}...", tableName);
            try {
                try (Statement stmt = conn.createStatement()) {
                    stmt.execute(leaderElectionDatabaseStructure.createSQLRenameTimestampColumn());
                }
                LOG.info("Schema migration of table {} completed successfully.", tableName);
                columns.remove("timestamp");
                columns.add("lease_ts");
            } catch (SQLException e) {
                // Concurrent migration: another node may have renamed the column simultaneously — re-check before failing.
                columns = getExistingColumns(conn);
                if (!columns.contains("lease_ts")) {
                    throw e;
                }
                if (LOG.isDebugEnabled()) {
                    LOG.debug("Column 'timestamp' in table {} was renamed concurrently by another node.", tableName);
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

        // Try exact case, then uppercase (Oracle), then lowercase (PostgreSQL) — databases differ
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
