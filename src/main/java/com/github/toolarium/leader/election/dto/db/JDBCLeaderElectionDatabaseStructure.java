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
        return "CREATE TABLE " + getTableName() + "(id varchar(36) not NULL, name varchar(512) not NULL, instance varchar(255), username varchar(255), lease_ts bigint not NULL, force_release_deadline bigint NULL, PRIMARY KEY (name))";
    }


    /**
     * @see com.github.toolarium.leader.election.dto.db.DatabaseLeaderElectionConfiguration.LeaderElectionDatabaseStructure#createSQLIndex()
     *     Returns a secondary index on the {@code instance} column to support the admin force-release
     *     path ({@code deleteLeaderByInstance()}) without a full table scan.
     *     The PRIMARY KEY on {@code name} already covers all hot-path per-tick queries.
     */
    @Override
    public String createSQLIndex() {
        String safeName = getTableName().toLowerCase().replaceAll("[^a-z0-9_]", "_");
        return "CREATE INDEX idx_" + safeName + "_instance ON " + getTableName() + "(instance)";
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
     * @see com.github.toolarium.leader.election.dto.db.DatabaseLeaderElectionConfiguration.LeaderElectionDatabaseStructure#createSQLDeleteLeaderByNameAndInstance()
     */
    @Override
    public String createSQLDeleteLeaderByNameAndInstance() {
        return "DELETE FROM " + getTableName() + " WHERE name = ? AND instance = ?";
    }


    /**
     * @see com.github.toolarium.leader.election.dto.db.DatabaseLeaderElectionConfiguration.LeaderElectionDatabaseStructure#createSQLDeleteLeaderRecord()
     */
    @Override
    public String createSQLDeleteLeaderRecord() {
        return "DELETE FROM " + getTableName() + " WHERE name = ? AND instance = ? AND id = ?";
    }


    /**
     * @see com.github.toolarium.leader.election.dto.db.DatabaseLeaderElectionConfiguration.LeaderElectionDatabaseStructure#createSQLDeleteLeaderByInstance()
     */
    @Override
    public String createSQLDeleteLeaderByInstance() {
        return "DELETE FROM " + getTableName() + " WHERE instance = ?";
    }


    /**
     * @see com.github.toolarium.leader.election.dto.db.DatabaseLeaderElectionConfiguration.LeaderElectionDatabaseStructure#createSQLSelectAll()
     */
    @Override
    public String createSQLSelectAll() {
        return "SELECT id, name, instance, username, lease_ts, force_release_deadline FROM " + getTableName();
    }


    /**
     * @see com.github.toolarium.leader.election.dto.db.DatabaseLeaderElectionConfiguration.LeaderElectionDatabaseStructure#createSQLSelectLeaderByName()
     */
    @Override
    public String createSQLSelectLeaderByName() {
        return "SELECT id, name, instance, username, lease_ts, force_release_deadline FROM " + getTableName() + " WHERE name = ?";
    }


    /**
     * @see com.github.toolarium.leader.election.dto.db.DatabaseLeaderElectionConfiguration.LeaderElectionDatabaseStructure#createSQLUpdateLeaderTimestamp()
     */
    @Override
    public String createSQLUpdateLeaderTimestamp() {
        return "UPDATE " + getTableName() + " SET id = ?, lease_ts = ? WHERE name = ? AND instance = ? AND force_release_deadline IS NULL";
    }


    /**
     * @see com.github.toolarium.leader.election.dto.db.DatabaseLeaderElectionConfiguration.LeaderElectionDatabaseStructure#createSQLStealLeadership()
     */
    @Override
    public String createSQLStealLeadership() {
        return "UPDATE " + getTableName()
                + " SET id = ?, instance = ?, username = ?, lease_ts = ?, force_release_deadline = NULL"
                + " WHERE name = ? AND id = ? AND (lease_ts < ? OR (force_release_deadline IS NOT NULL AND force_release_deadline <= ?))";
    }


    /**
     * @see com.github.toolarium.leader.election.dto.db.DatabaseLeaderElectionConfiguration.LeaderElectionDatabaseStructure#createSQLSetForceReleaseDeadline()
     */
    @Override
    public String createSQLSetForceReleaseDeadline() {
        return "UPDATE " + getTableName() + " SET force_release_deadline = ? WHERE instance = ? AND force_release_deadline IS NULL";
    }


    /**
     * @see com.github.toolarium.leader.election.dto.db.DatabaseLeaderElectionConfiguration.LeaderElectionDatabaseStructure#createSQLAddForceReleaseDeadlineColumn()
     */
    @Override
    public String createSQLAddForceReleaseDeadlineColumn() {
        return "ALTER TABLE " + getTableName() + " ADD COLUMN force_release_deadline BIGINT NULL";
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
