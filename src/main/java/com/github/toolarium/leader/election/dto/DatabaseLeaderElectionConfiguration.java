/*
 * DatabaseLeaderElectionConfiguration.java
 *
 * Copyright by toolarium, all rights reserved.
 */
package com.github.toolarium.leader.election.dto;

import java.time.Duration;
import javax.sql.DataSource;

/**
 * The leader election database configuration
 * 
 * @author patrick
 */
public class DatabaseLeaderElectionConfiguration extends LeaderElectionConfiguration {
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
     */
    public void setDataSource(DataSource dataSource) {
        this.dataSource = dataSource;
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
     */
    public void setLeaderElectionDatabaseStructure(LeaderElectionDatabaseStructure leaderElectionDatabaseStructure) {
        this.leaderElectionDatabaseStructure = leaderElectionDatabaseStructure;
    }

    
    /**
     * @see com.github.toolarium.leader.election.dto.LeaderElectionConfiguration#validate()
     */
    protected void validate() {
        super.validate();
        leaderElectionDatabaseStructure = new LeaderElectionDatabaseStructure();
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
         * Set the table name
         *
         * @param tableName the table name
         */
        public void setTableName(String tableName) {
            this.tableName = tableName;
        }

        
        /**
         * Create the leader election table query
         *
         * @return the leader election table query
         */
        public String createSQLTable() {
            return "CREATE TABLE " + getTableName() + "(id varchar(36) not NULL, name varchar(512) not NULL, instance varchar(255), username varchar(255), timestamp varchar(25) not NULL, PRIMARY KEY (name));"; 
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
            return "INSERT INTO " + getTableName() + "(id, name, instance, username, timestamp) VALUES (?, ?, ?, ?, ?);"; 
        }

        
        /**
         * Create the insert statement of the leader election table
         *
         * @return the insert statement
         */
        public String createSQLDeleteLeaderByName() {
            return "DELETE FROM " + getTableName() + " WHERE name = ?;"; 
        }

        
        /**
         * Create the insert statement of the leader election table
         *
         * @return the insert statement
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
            return "SELECT id, name, instance, username, timestamp FROM " + getTableName() + " WHERE name = ?;";
        }


        /**
         * Create the update statement for renewing the leader timestamp.
         * Updates id and timestamp only when name and instance both match, ensuring atomic renewal.
         *
         * @return the update statement
         */
        public String createSQLUpdateLeaderTimestamp() {
            return "UPDATE " + getTableName() + " SET id = ?, timestamp = ? WHERE name = ? AND instance = ?;";
        }
    }
}
