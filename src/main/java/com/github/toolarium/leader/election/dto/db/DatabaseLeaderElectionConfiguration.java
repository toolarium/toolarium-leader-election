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
     *   <li>Version 1 — initial schema with {@code lease_ts} (renamed from legacy {@code timestamp} column).</li>
     * </ul>
     */
    public static final int SCHEMA_VERSION = 1;

    private DataSource dataSource;
    private LeaderElectionDatabaseStructure leaderElectionDatabaseStructure;


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
     * @see com.github.toolarium.leader.election.dto.LeaderElectionConfiguration#validate()
     */
    protected void validate() {
        super.validate();
        if (leaderElectionDatabaseStructure == null) {
            leaderElectionDatabaseStructure = new JDBCLeaderElectionDatabaseStructure();
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
            return "CREATE TABLE " + getTableName() + "(id varchar(36) not NULL, name varchar(512) not NULL, instance varchar(255), username varchar(255), lease_ts bigint not NULL, PRIMARY KEY (name));";
        }


        /**
         * Create the leader election index
         *
         * @return the leader election index
         */
        public String createSQLIndex() {
            return "CREATE INDEX idx_" + getTableName() + " on " + getTableName() + "(name);";
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
         * Create the delete-by-name-and-id statement of the leader election table
         *
         * @return the delete statement
         */
        public String createSQLDeleteLeaderByNameAndId() {
            return "DELETE FROM " + getTableName() + " WHERE name = ? AND id = ?;";
        }


        /**
         * Create the select statement of the leader election table by name
         *
         * @return the leader election table query
         */
        public String createSQLSelectLeaderByName() {
            return "SELECT id, name, instance, username, lease_ts FROM " + getTableName() + " WHERE name = ?;";
        }


        /**
         * Create the update statement for renewing the leader timestamp.
         * Updates id and lease_ts only when name and instance both match, ensuring atomic renewal.
         *
         * @return the update statement
         */
        public String createSQLUpdateLeaderTimestamp() {
            return "UPDATE " + getTableName() + " SET id = ?, lease_ts = ? WHERE name = ? AND instance = ?;";
        }


        /**
         * Create the update statement for atomically stealing an expired lease.
         * Only succeeds when the row identified by name and id still exists and its
         * lease_ts is older than the supplied expiry threshold (epoch milliseconds).
         * Parameters: newId, newInstance, newUsername, newLease_ts, name, currentLeaderId, expiryThreshold
         *
         * @return the steal-leadership update statement
         */
        public String createSQLStealLeadership() {
            return "UPDATE " + getTableName()
                    + " SET id = ?, instance = ?, username = ?, lease_ts = ?"
                    + " WHERE name = ? AND id = ? AND lease_ts < ?;";
        }


        /**
         * Create the query that returns the database server's current time as a TIMESTAMP.
         * The caller reads column 1 as a {@code java.sql.Timestamp} and converts via
         * {@code Timestamp.getTime()} to epoch milliseconds.
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
            return new LinkedHashSet<>(Arrays.asList("id", "name", "instance", "username", "lease_ts"));
        }
    }
}
