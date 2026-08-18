/*
 * SQLServerLeaderElectionDatabaseStructure.java
 *
 * Copyright by toolarium, all rights reserved.
 */
package com.github.toolarium.leader.election.dto;


/**
 * Defines the SQL Server database structure using NVARCHAR. The PRIMARY KEY already creates
 * a clustered index on the key column; no additional index is needed.
 *
 * @author patrick
 */
public class SQLServerLeaderElectionDatabaseStructure extends JDBCLeaderElectionDatabaseStructure {

    /**
     * Constructor for SQLServerLeaderElectionDatabaseStructure
     */
    public SQLServerLeaderElectionDatabaseStructure() {
        super();
    }


    /**
     * @see com.github.toolarium.leader.election.dto.JDBCLeaderElectionDatabaseStructure#createSQLTable()
     */
    @Override
    public String createSQLTable() {
        return "CREATE TABLE " + getTableName() + "(id NVARCHAR(36) NOT NULL, name NVARCHAR(512) NOT NULL, "
                + "instance NVARCHAR(255), username NVARCHAR(255), timestamp NVARCHAR(25) NOT NULL, "
                + "CONSTRAINT pk_" + getTableName().toLowerCase() + " PRIMARY KEY (name))";
    }


    /**
     * @see com.github.toolarium.leader.election.dto.JDBCLeaderElectionDatabaseStructure#createSQLIndex()
     *     SQL Server PRIMARY KEY already creates a clustered index on the key column; no additional index is needed.
     */
    @Override
    public String createSQLIndex() {
        return null;
    }
}
