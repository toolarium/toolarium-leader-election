/*
 * JDBCLeaderElectionDatabaseStructure.java
 *
 * Copyright by toolarium, all rights reserved.
 */
package com.github.toolarium.leader.election.dto;


/**
 * Defines the standard database structure without trailing semicolons (compatible with PostgreSQL and standard JDBC).
 *
 * @author patrick
 */
public class JDBCLeaderElectionDatabaseStructure extends DatabaseLeaderElectionConfiguration.LeaderElectionDatabaseStructure {

    /**
     * Constructor for JDBCLeaderElectionDatabaseStructure
     */
    public JDBCLeaderElectionDatabaseStructure() {
        super();
    }


    /**
     * @see com.github.toolarium.leader.election.dto.DatabaseLeaderElectionConfiguration.LeaderElectionDatabaseStructure#createSQLTable()
     */
    @Override
    public String createSQLTable() {
        return "CREATE TABLE " + getTableName() + "(id varchar(36) not NULL, name varchar(512) not NULL, instance varchar(255), username varchar(255), timestamp varchar(25) not NULL, PRIMARY KEY (name))";
    }


    /**
     * @see com.github.toolarium.leader.election.dto.DatabaseLeaderElectionConfiguration.LeaderElectionDatabaseStructure#createSQLIndex()
     */
    @Override
    public String createSQLIndex() {
        return "CREATE INDEX idx_" + getTableName() + " on " + getTableName() + "(name)";
    }


    /**
     * @see com.github.toolarium.leader.election.dto.DatabaseLeaderElectionConfiguration.LeaderElectionDatabaseStructure#createSQLDropTable()
     */
    @Override
    public String createSQLDropTable() {
        return "DROP TABLE " + getTableName();
    }


    /**
     * @see com.github.toolarium.leader.election.dto.DatabaseLeaderElectionConfiguration.LeaderElectionDatabaseStructure#createSQLInsertLeader()
     */
    @Override
    public String createSQLInsertLeader() {
        return "INSERT INTO " + getTableName() + "(id, name, instance, username, timestamp) VALUES (?, ?, ?, ?, ?)";
    }


    /**
     * @see com.github.toolarium.leader.election.dto.DatabaseLeaderElectionConfiguration.LeaderElectionDatabaseStructure#createSQLDeleteLeaderByName()
     */
    @Override
    public String createSQLDeleteLeaderByName() {
        return "DELETE FROM " + getTableName() + " WHERE name = ?";
    }


    /**
     * @see com.github.toolarium.leader.election.dto.DatabaseLeaderElectionConfiguration.LeaderElectionDatabaseStructure#createSQLDeleteLeaderByNameAndId()
     */
    @Override
    public String createSQLDeleteLeaderByNameAndId() {
        return "DELETE FROM " + getTableName() + " WHERE name = ? AND id = ?";
    }


    /**
     * @see com.github.toolarium.leader.election.dto.DatabaseLeaderElectionConfiguration.LeaderElectionDatabaseStructure#createSQLSelectLeaderByName()
     */
    @Override
    public String createSQLSelectLeaderByName() {
        return "SELECT id, name, instance, username, timestamp FROM " + getTableName() + " WHERE name = ?";
    }


    /**
     * @see com.github.toolarium.leader.election.dto.DatabaseLeaderElectionConfiguration.LeaderElectionDatabaseStructure#createSQLUpdateLeaderTimestamp()
     */
    @Override
    public String createSQLUpdateLeaderTimestamp() {
        return "UPDATE " + getTableName() + " SET id = ?, timestamp = ? WHERE name = ? AND instance = ?";
    }
}
