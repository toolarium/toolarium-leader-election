/*
 * JDBCLeaderElectionDatabaseStructure.java
 *
 * Copyright by toolarium, all rights reserved.
 */
package com.github.toolarium.leader.election.dto.db;


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
     * @see com.github.toolarium.leader.election.dto.db.DatabaseLeaderElectionConfiguration.LeaderElectionDatabaseStructure#createSQLTable()
     */
    @Override
    public String createSQLTable() {
        return "CREATE TABLE " + getTableName() + "(id varchar(36) not NULL, name varchar(512) not NULL, instance varchar(255), username varchar(255), lease_ts bigint not NULL, PRIMARY KEY (name))";
    }


    /**
     * @see com.github.toolarium.leader.election.dto.db.DatabaseLeaderElectionConfiguration.LeaderElectionDatabaseStructure#createSQLIndex()
     */
    @Override
    public String createSQLIndex() {
        return "CREATE INDEX idx_" + getTableName() + " on " + getTableName() + "(name)";
    }


    /**
     * @see com.github.toolarium.leader.election.dto.db.DatabaseLeaderElectionConfiguration.LeaderElectionDatabaseStructure#createSQLDropTable()
     */
    @Override
    public String createSQLDropTable() {
        return "DROP TABLE " + getTableName();
    }


    /**
     * @see com.github.toolarium.leader.election.dto.db.DatabaseLeaderElectionConfiguration.LeaderElectionDatabaseStructure#createSQLInsertLeader()
     */
    @Override
    public String createSQLInsertLeader() {
        return "INSERT INTO " + getTableName() + "(id, name, instance, username, lease_ts) VALUES (?, ?, ?, ?, ?)";
    }


    /**
     * @see com.github.toolarium.leader.election.dto.db.DatabaseLeaderElectionConfiguration.LeaderElectionDatabaseStructure#createSQLDeleteLeaderByName()
     */
    @Override
    public String createSQLDeleteLeaderByName() {
        return "DELETE FROM " + getTableName() + " WHERE name = ?";
    }


    /**
     * @see com.github.toolarium.leader.election.dto.db.DatabaseLeaderElectionConfiguration.LeaderElectionDatabaseStructure#createSQLDeleteLeaderByNameAndId()
     */
    @Override
    public String createSQLDeleteLeaderByNameAndId() {
        return "DELETE FROM " + getTableName() + " WHERE name = ? AND id = ?";
    }


    /**
     * @see com.github.toolarium.leader.election.dto.db.DatabaseLeaderElectionConfiguration.LeaderElectionDatabaseStructure#createSQLSelectLeaderByName()
     */
    @Override
    public String createSQLSelectLeaderByName() {
        return "SELECT id, name, instance, username, lease_ts FROM " + getTableName() + " WHERE name = ?";
    }


    /**
     * @see com.github.toolarium.leader.election.dto.db.DatabaseLeaderElectionConfiguration.LeaderElectionDatabaseStructure#createSQLUpdateLeaderTimestamp()
     */
    @Override
    public String createSQLUpdateLeaderTimestamp() {
        return "UPDATE " + getTableName() + " SET id = ?, lease_ts = ? WHERE name = ? AND instance = ?";
    }


    /**
     * @see com.github.toolarium.leader.election.dto.db.DatabaseLeaderElectionConfiguration.LeaderElectionDatabaseStructure#createSQLStealLeadership()
     */
    @Override
    public String createSQLStealLeadership() {
        return "UPDATE " + getTableName()
                + " SET id = ?, instance = ?, username = ?, lease_ts = ?"
                + " WHERE name = ? AND id = ? AND lease_ts < ?";
    }


    /**
     * @see com.github.toolarium.leader.election.dto.db.DatabaseLeaderElectionConfiguration.LeaderElectionDatabaseStructure#createSQLSelectCurrentTimestampMillis()
     */
    @Override
    public String createSQLSelectCurrentTimestampMillis() {
        return "SELECT CURRENT_TIMESTAMP";
    }


    /**
     * @see com.github.toolarium.leader.election.dto.db.DatabaseLeaderElectionConfiguration.LeaderElectionDatabaseStructure#createSQLRenameTimestampColumn()
     *     Double-quoting {@code "timestamp"} is required for H2 (reserved keyword) and ensures
     *     an exact case-sensitive match in PostgreSQL.
     */
    @Override
    public String createSQLRenameTimestampColumn() {
        return "ALTER TABLE " + getTableName() + " RENAME COLUMN \"timestamp\" TO lease_ts";
    }
}
