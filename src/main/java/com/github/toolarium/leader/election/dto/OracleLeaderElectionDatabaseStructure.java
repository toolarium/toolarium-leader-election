/*
 * OracleLeaderElectionDatabaseStructure.java
 *
 * Copyright by toolarium, all rights reserved.
 */
package com.github.toolarium.leader.election.dto;


/**
 * Defines the Oracle database structure using VARCHAR2 and no trailing semicolons.
 *
 * @author patrick
 */
public class OracleLeaderElectionDatabaseStructure extends JDBCLeaderElectionDatabaseStructure {

    /**
     * Constructor for OracleLeaderElectionDatabaseStructure
     */
    public OracleLeaderElectionDatabaseStructure() {
        super();
    }


    /**
     * @see com.github.toolarium.leader.election.dto.JDBCLeaderElectionDatabaseStructure#createSQLTable()
     */
    @Override
    public String createSQLTable() {
        return "CREATE TABLE " + getTableName() + "(id VARCHAR2(36) NOT NULL, name VARCHAR2(512) NOT NULL, "
                + "instance VARCHAR2(255), username VARCHAR2(255), timestamp VARCHAR2(25) NOT NULL, "
                + "CONSTRAINT pk_" + getTableName().toLowerCase() + " PRIMARY KEY (name))";
    }


    /**
     * @see com.github.toolarium.leader.election.dto.JDBCLeaderElectionDatabaseStructure#createSQLIndex()
     *     Oracle PRIMARY KEY already creates an index on the key column; no additional index is needed.
     */
    @Override
    public String createSQLIndex() {
        return null;
    }
}
