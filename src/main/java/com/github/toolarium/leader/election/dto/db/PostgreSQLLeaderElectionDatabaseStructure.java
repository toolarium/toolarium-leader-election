/*
 * PostgreSQLLeaderElectionDatabaseStructure.java
 *
 * Copyright by toolarium, all rights reserved.
 */
package com.github.toolarium.leader.election.dto.db;


/**
 * Defines the PostgreSQL database structure. PostgreSQL limits unquoted identifiers
 * to {@value #MAX_TABLE_NAME_LENGTH} characters (NAMEDATALEN - 1).
 *
 * @author patrick
 */
public class PostgreSQLLeaderElectionDatabaseStructure extends JDBCLeaderElectionDatabaseStructure {
    /** Maximum table name length for PostgreSQL (NAMEDATALEN - 1). */
    public static final int MAX_TABLE_NAME_LENGTH = 63;


    /**
     * Constructor for PostgreSQLLeaderElectionDatabaseStructure
     */
    public PostgreSQLLeaderElectionDatabaseStructure() {
        super();
    }


    /**
     * @see com.github.toolarium.leader.election.dto.db.JDBCLeaderElectionDatabaseStructure#getMaxTableNameLength()
     */
    @Override
    public int getMaxTableNameLength() {
        return MAX_TABLE_NAME_LENGTH;
    }
}
