/*
 * MySQLLeaderElectionDatabaseStructure.java
 *
 * Copyright by toolarium, all rights reserved.
 */
package com.github.toolarium.leader.election.dto.db;


/**
 * Defines the MySQL database structure. The PRIMARY KEY already creates a clustered index,
 * so no additional index is needed. ROW_FORMAT=DYNAMIC is required so that the varchar(512)
 * primary key column fits within InnoDB's index key limit.
 *
 * @author patrick
 */
public class MySQLLeaderElectionDatabaseStructure extends JDBCLeaderElectionDatabaseStructure {
    /** Maximum table name length for MySQL. */
    public static final int MAX_TABLE_NAME_LENGTH = 64;


    /**
     * Constructor for MySQLLeaderElectionDatabaseStructure
     */
    public MySQLLeaderElectionDatabaseStructure() {
        super();
    }


    /**
     * @see com.github.toolarium.leader.election.dto.db.JDBCLeaderElectionDatabaseStructure#getMaxTableNameLength()
     */
    @Override
    public int getMaxTableNameLength() {
        return MAX_TABLE_NAME_LENGTH;
    }


    /**
     * @see com.github.toolarium.leader.election.dto.db.JDBCLeaderElectionDatabaseStructure#createSQLTable()
     */
    @Override
    public String createSQLTable() {
        return "CREATE TABLE " + getTableName() + "(id varchar(36) NOT NULL, name varchar(512) NOT NULL, "
                + "instance varchar(255), username varchar(255), lease_ts bigint NOT NULL, "
                + "force_release_deadline bigint NULL, "
                + "CONSTRAINT pk_" + getConstraintBaseName() + " PRIMARY KEY (name)) ROW_FORMAT=DYNAMIC";
    }


    /**
     * @see com.github.toolarium.leader.election.dto.db.DatabaseLeaderElectionConfiguration.LeaderElectionDatabaseStructure#createSQLRenameTimestampColumn()
     *     MySQL and MariaDB treat {@code timestamp} as a reserved word and require backtick quoting.
     *     {@code RENAME COLUMN} is supported since MySQL 8.0.4 and MariaDB 10.5.2.
     */
    @Override
    public String createSQLRenameTimestampColumn() {
        return "ALTER TABLE " + getTableName() + " RENAME COLUMN `timestamp` TO lease_ts";
    }


    /**
     * @see com.github.toolarium.leader.election.dto.db.JDBCLeaderElectionDatabaseStructure#createSQLSelectCurrentTimestampMillis()
     *     {@code CURRENT_TIMESTAMP} is a zone-less DATETIME in the session time zone; reading it via
     *     {@code ResultSet.getTimestamp()} interprets it in the JVM time zone, so nodes running in
     *     different time zones would compute different lease clocks. {@code UNIX_TIMESTAMP()} converts
     *     on the server side and returns the epoch value directly.
     */
    @Override
    public String createSQLSelectCurrentTimestampMillis() {
        return "SELECT CAST(UNIX_TIMESTAMP(CURRENT_TIMESTAMP(3)) * 1000 AS SIGNED)";
    }
}
