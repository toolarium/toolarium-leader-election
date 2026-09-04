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

    /**
     * Constructor for MySQLLeaderElectionDatabaseStructure
     */
    public MySQLLeaderElectionDatabaseStructure() {
        super();
    }


    /**
     * @see com.github.toolarium.leader.election.dto.db.JDBCLeaderElectionDatabaseStructure#createSQLTable()
     */
    @Override
    public String createSQLTable() {
        return "CREATE TABLE " + getTableName() + "(id varchar(36) NOT NULL, name varchar(512) NOT NULL, "
                + "instance varchar(255), username varchar(255), lease_ts bigint NOT NULL, "
                + "CONSTRAINT pk_" + getTableName().toLowerCase() + " PRIMARY KEY (name)) ROW_FORMAT=DYNAMIC";
    }


    /**
     * @see com.github.toolarium.leader.election.dto.db.JDBCLeaderElectionDatabaseStructure#createSQLIndex()
     *     MySQL PRIMARY KEY already creates a clustered index on the key column; no additional index is needed.
     */
    @Override
    public String createSQLIndex() {
        return null;
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
}
