/*
 * MySQLLeaderElectionDatabaseStructure.java
 *
 * Copyright by toolarium, all rights reserved.
 */
package com.github.toolarium.leader.election.dto;


/**
 * Defines the MySQL database structure. The PRIMARY KEY already creates a clustered index,
 * so no additional index is needed.
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
     * @see com.github.toolarium.leader.election.dto.JDBCLeaderElectionDatabaseStructure#createSQLTable()
     */
    @Override
    public String createSQLTable() {
        return "CREATE TABLE " + getTableName() + "(id varchar(36) NOT NULL, name varchar(512) NOT NULL, "
                + "instance varchar(255), username varchar(255), timestamp varchar(25) NOT NULL, "
                + "CONSTRAINT pk_" + getTableName().toLowerCase() + " PRIMARY KEY (name))";
    }


    /**
     * @see com.github.toolarium.leader.election.dto.JDBCLeaderElectionDatabaseStructure#createSQLIndex()
     *     MySQL PRIMARY KEY already creates a clustered index on the key column; no additional index is needed.
     */
    @Override
    public String createSQLIndex() {
        return null;
    }
}
