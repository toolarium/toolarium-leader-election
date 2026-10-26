/*
 * DatabaseLeaderElectionConfiguration.java
 *
 * Copyright by toolarium, all rights reserved.
 */
package com.github.toolarium.leader.election.dto.db;

import com.github.toolarium.leader.election.dto.LeaderElectionConfiguration;
import java.time.Duration;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Set;
import javax.sql.DataSource;

/**
 * The leader election database configuration
 *
 * @author patrick
 */
public class DatabaseLeaderElectionConfiguration extends LeaderElectionConfiguration {
    /**
     * Current schema version. Increment this constant and add a corresponding migration
     * step in {@code LeaderElectionDAO.validateAndMigrateSchema()} whenever the DDL changes.
     * <ul>
     *   <li>Version 1 - initial schema with {@code lease_ts} (renamed from legacy {@code timestamp} column).</li>
     * </ul>
     */
    public static final int SCHEMA_VERSION = 1;

    // volatile: set when the elector is built, read by the election and the administration threads
    private volatile DataSource dataSource;
    private volatile LeaderElectionDatabaseStructure leaderElectionDatabaseStructure;
    // When false, elect() fails fast with a clear error instead of executing CREATE TABLE or
    // ALTER TABLE at startup. Set to false for environments where the application user has no
    // DDL rights or when the table is managed by an external migration tool (Flyway, Liquibase).
    private boolean allowDdl = true;


    /**
     * Constructor for DatabaseLeaderElectionConfiguration
     *
     * @throws IllegalArgumentException In case of a parameter failure
     */
    public DatabaseLeaderElectionConfiguration() throws IllegalArgumentException {
        super();
    }


    /**
     * Constructor for DatabaseLeaderElectionConfiguration
     *
     * @param timeoutInSeconds the timeout
     * @throws IllegalArgumentException In case of a parameter failure
     */
    public DatabaseLeaderElectionConfiguration(long timeoutInSeconds) throws IllegalArgumentException {
        super(timeoutInSeconds);
    }


    /**
     * Constructor for DatabaseLeaderElectionConfiguration
     *
     * @param timeout the timeout
     * @param renewDeadline the renew deadline
     * @param retryPeriod the retry period
     * @throws IllegalArgumentException In case of a parameter failure
     */
    public DatabaseLeaderElectionConfiguration(Duration timeout, Duration renewDeadline, Duration retryPeriod) throws IllegalArgumentException {
        super(timeout, renewDeadline, retryPeriod);
    }


    /**
     * Get the data source
     *
     * @return the data source
     */
    public DataSource getDataSource() {
        return dataSource;
    }


    /**
     * Set the data source
     *
     * @param dataSource the data source
     * @return this configuration instance for method chaining
     */
    public DatabaseLeaderElectionConfiguration setDataSource(DataSource dataSource) {
        this.dataSource = dataSource;
        return this;
    }


    /**
     * Get the leader election database structure
     *
     * @return the leader election database structure
     */
    public LeaderElectionDatabaseStructure getLeaderElectionDatabaseStructure() {
        return leaderElectionDatabaseStructure;
    }


    /**
     * Set the leader election database structure
     *
     * @param leaderElectionDatabaseStructure the leader election database structure
     * @return this configuration instance for method chaining
     */
    public DatabaseLeaderElectionConfiguration setLeaderElectionDatabaseStructure(LeaderElectionDatabaseStructure leaderElectionDatabaseStructure) {
        this.leaderElectionDatabaseStructure = leaderElectionDatabaseStructure;
        return this;
    }


    /**
     * Returns whether the DAO is permitted to execute DDL statements (CREATE TABLE, ALTER TABLE)
     * at startup. Defaults to {@code true}.
     *
     * <p>Set to {@code false} when the application database user has no DDL rights, or when the
     * table is managed by an external schema-migration tool (Flyway, Liquibase, etc.). With DDL
     * disabled, {@code elect()} throws a descriptive error instead of attempting DDL, and the
     * error message includes the expected DDL for the missing table or column so operators can
     * apply it manually.
     *
     * @return {@code true} if DDL is allowed, {@code false} if DDL is forbidden
     */
    public boolean isAllowDdl() {
        return allowDdl;
    }


    /**
     * Set whether the DAO may execute DDL statements at startup.
     *
     * @param allowDdl {@code false} to disable runtime DDL; {@code true} (default) to allow it
     * @return this configuration instance for method chaining
     */
    public DatabaseLeaderElectionConfiguration setAllowDdl(boolean allowDdl) {
        this.allowDdl = allowDdl;
        return this;
    }


    /**
     * @see com.github.toolarium.leader.election.dto.LeaderElectionConfiguration#validate()
     */
    public void validate() {
        super.validate();
        // leaderElectionDatabaseStructure may be null here; the DAO auto-detects the
        // correct dialect from the JDBC product name on first connection if none is set.
    }


    /**
     * Validate that the DataSource is set. Called after all setters have been applied
     * (not from the constructor, since the DataSource is typically set after construction).
     *
     * @throws IllegalArgumentException if the DataSource is null
     */
    public void validateDataSource() throws IllegalArgumentException {
        if (dataSource == null) {
            throw new IllegalArgumentException("DataSource must not be null. Set it via setDataSource() or the builder's dataSource() method.");
        }
    }


    /**
     * Defines the database structure
     *
     * @author patrick
     */
    public static class LeaderElectionDatabaseStructure {
        private String tableName;

        /**
         * Constructor for DatabaseLeaderElectionConfiguration.LeaderElectionDatabaseStructure
         */
        public LeaderElectionDatabaseStructure() {
            this.tableName = "LeaderElection";
        }


        /**
         * Get the table name
         *
         * @return the table name
         */
        public String getTableName() {
            return tableName;
        }


        /**
         * Set the table name.
         * Only letters, digits, underscore, dollar sign, and dot (for schema qualification) are permitted.
         * Quotes, semicolons, spaces, and other SQL-special characters are rejected to prevent
         * SQL injection via the table name in all generated DDL and DML statements.
         *
         * @param tableName the table name
         * @throws IllegalArgumentException if the table name is null, blank, or contains invalid characters
         */
        public void setTableName(String tableName) {
            if (tableName == null || tableName.isBlank()) {
                throw new IllegalArgumentException("Table name must not be null or blank.");
            }
            if (!tableName.matches("[A-Za-z_][A-Za-z0-9_$.]*")) {
                throw new IllegalArgumentException("Table name contains invalid characters: '" + tableName + "'. Only letters, digits, underscore, dollar sign, and dot are allowed.");
            }
            this.tableName = tableName;
        }


        /**
         * Create the leader election table query
         *
         * @return the leader election table query
         */
        public String createSQLTable() {
            return "CREATE TABLE " + getTableName() + "(id varchar(36) not NULL, name varchar(512) not NULL, instance varchar(255), username varchar(255), lease_ts bigint not NULL, force_release_deadline bigint NULL, PRIMARY KEY (name));";
        }


        /**
         * Create the leader election index.
         * Returns {@code null} because the PRIMARY KEY on {@code name} already creates an index
         * in every supported database. Subclasses may override if an explicit secondary index
         * is needed for a non-standard dialect.
         *
         * @return {@code null} (no additional index required)
         */
        public String createSQLIndex() {
            return null;
        }


        /**
         * Create the drop table query
         *
         * @return the the drop table query
         */
        public String createSQLDropTable() {
            return "DROP TABLE " + getTableName() + ";";
        }


        /**
         * Create the insert statement of the leader election table
         *
         * @return the insert statement
         */
        public String createSQLInsertLeader() {
            return "INSERT INTO " + getTableName() + "(id, name, instance, username, lease_ts) VALUES (?, ?, ?, ?, ?);";
        }


        /**
         * Create the delete-by-name statement of the leader election table
         *
         * @return the delete statement
         */
        public String createSQLDeleteLeaderByName() {
            return "DELETE FROM " + getTableName() + " WHERE name = ?;";
        }


        /**
         * Create the delete-by-name-and-instance statement of the leader election table.
         * Uses the stable elector instance identifier so the delete succeeds even when a
         * concurrent scheduler tick has already renewed the per-renewal {@code id} column.
         *
         * @return the delete statement
         */
        public String createSQLDeleteLeaderByNameAndInstance() {
            return "DELETE FROM " + getTableName() + " WHERE name = ? AND instance = ?;";
        }


        /**
         * Create the statement that deletes the row of this instance only while it still holds the given record id, i.e. was
         * not renewed since. Parameters: name, instance, id
         *
         * @return the delete statement
         */
        public String createSQLDeleteLeaderRecord() {
            return "DELETE FROM " + getTableName() + " WHERE name = ? AND instance = ? AND id = ?;";
        }


        /**
         * Create the delete-by-instance statement of the leader election table.
         * Deletes the row for the given stable elector instance id regardless of election group name.
         * Used by the administration API to force-release a remote leader in a single round-trip.
         *
         * @return the delete statement
         */
        public String createSQLDeleteLeaderByInstance() {
            return "DELETE FROM " + getTableName() + " WHERE instance = ?;";
        }


        /**
         * Create the select statement to return all stored leader election records.
         *
         * @return the select-all statement
         */
        public String createSQLSelectAll() {
            return "SELECT id, name, instance, username, lease_ts, force_release_deadline FROM " + getTableName() + ";";
        }


        /**
         * Create the select statement of the leader election table by name
         *
         * @return the leader election table query
         */
        public String createSQLSelectLeaderByName() {
            return "SELECT id, name, instance, username, lease_ts, force_release_deadline FROM " + getTableName() + " WHERE name = ?;";
        }


        /**
         * Create the update statement for renewing the leader timestamp.
         * Updates id and lease_ts only when name and instance both match, the row still holds the record id the renewer
         * wrote or read last, AND no admin force-release deadline is pending. The record id keeps a late renewal of a closed
         * session from overwriting the row of a later session of the same instance; the deadline prevents a force-released
         * holder from inadvertently extending its own lease before it discovers it.
         * Parameters: newId, lease_ts, name, instance, expectedId
         *
         * @return the update statement
         */
        public String createSQLUpdateLeaderTimestamp() {
            return "UPDATE " + getTableName() + " SET id = ?, lease_ts = ? WHERE name = ? AND instance = ? AND id = ? AND force_release_deadline IS NULL;";
        }


        /**
         * Create the update statement for atomically stealing an expired or force-released lease.
         * Only succeeds when the row identified by name and id still exists and either the
         * lease has expired (lease_ts older than expiryThreshold) OR an admin force-release
         * deadline has elapsed (force_release_deadline is set and in the past). A lease or deadline
         * after futureLimit cannot come from a healthy node and counts as expired, too.
         * The stolen row has force_release_deadline reset to NULL.
         * Parameters: newId, newInstance, newUsername, newLease_ts, name, currentLeaderId, expiryThreshold, futureLimit, dbNow, futureLimit
         *
         * @return the steal-leadership update statement
         */
        public String createSQLStealLeadership() {
            return "UPDATE " + getTableName()
                    + " SET id = ?, instance = ?, username = ?, lease_ts = ?, force_release_deadline = NULL"
                    + " WHERE name = ? AND id = ? AND (lease_ts < ? OR lease_ts > ?"
                + " OR (force_release_deadline IS NOT NULL AND (force_release_deadline <= ? OR force_release_deadline > ?)));";
        }


        /**
         * Create the update statement that sets the admin force-release deadline for a given instance.
         * Targeted by stable instance id so a single round-trip suffices regardless of election group name.
         * Parameters: deadlineMillis, instanceId
         *
         * @return the set-force-release-deadline update statement
         */
        public String createSQLSetForceReleaseDeadline() {
            return "UPDATE " + getTableName() + " SET force_release_deadline = ? WHERE instance = ? AND force_release_deadline IS NULL;";
        }


        /**
         * Create the DDL statement that adds the {@code force_release_deadline} column to an
         * existing leader-election table (schema migration from v1 to v2).
         * Subclasses must override when the target database uses non-standard ADD COLUMN syntax
         * (e.g. Oracle wraps the column definition in parentheses and uses NUMBER(19)).
         *
         * @return the add-column DDL statement
         */
        public String createSQLAddForceReleaseDeadlineColumn() {
            return "ALTER TABLE " + getTableName() + " ADD COLUMN force_release_deadline BIGINT NULL;";
        }


        /**
         * Create the query that returns the database server's current time. Column 1 may either be a
         * numeric epoch-milliseconds value (preferred, time-zone independent) or a TIMESTAMP that carries
         * time-zone information; a TIMESTAMP is converted via {@code Timestamp.getTime()}. Do not return
         * a zone-less local timestamp: it would be interpreted in the JVM time zone.
         *
         * @return the current-timestamp query
         */
        public String createSQLSelectCurrentTimestampMillis() {
            return "SELECT CURRENT_TIMESTAMP;";
        }


        /**
         * Create the DDL statement that renames the legacy {@code timestamp} column to
         * {@code lease_ts}. Used by the auto-migration path in
         * {@code LeaderElectionDAO.validateAndMigrateSchema()}.
         * Subclasses must override this when the target database uses non-standard syntax
         * (e.g. MySQL backtick quoting, SQL Server {@code sp_rename}).
         *
         * @return the rename-column DDL statement
         */
        public String createSQLRenameTimestampColumn() {
            return "ALTER TABLE " + getTableName() + " RENAME COLUMN timestamp TO lease_ts;";
        }


        /**
         * Return the set of column names (lowercase) that must be present in the
         * leader-election table. Used by schema validation on startup.
         *
         * @return expected column names in lowercase
         */
        public Set<String> getExpectedColumnNames() {
            return new LinkedHashSet<>(Arrays.asList("id", "name", "instance", "username", "lease_ts", "force_release_deadline"));
        }
    }
}
