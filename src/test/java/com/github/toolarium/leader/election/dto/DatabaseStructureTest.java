/*
 * DatabaseStructureTest.java
 *
 * Copyright by toolarium, all rights reserved.
 */
package com.github.toolarium.leader.election.dto;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;


/**
 * Test the database structure SQL generation for all supported databases.
 *
 * @author patrick
 */
public class DatabaseStructureTest {

    private static final String DEFAULT_TABLE = "LeaderElection";


    /**
     * Test the default (generic) LeaderElectionDatabaseStructure SQL statements.
     */
    @Test
    public void testDefaultStructure() {
        DatabaseLeaderElectionConfiguration.LeaderElectionDatabaseStructure s =
                new DatabaseLeaderElectionConfiguration.LeaderElectionDatabaseStructure();

        assertTrue(s.createSQLTable().contains(DEFAULT_TABLE));
        assertTrue(s.createSQLTable().endsWith(";"));
        assertTrue(s.createSQLIndex().contains(DEFAULT_TABLE));
        assertTrue(s.createSQLIndex().endsWith(";"));
        assertTrue(s.createSQLDropTable().contains(DEFAULT_TABLE));
        assertTrue(s.createSQLDropTable().endsWith(";"));
        assertTrue(s.createSQLInsertLeader().contains(DEFAULT_TABLE));
        assertTrue(s.createSQLInsertLeader().endsWith(";"));
        assertTrue(s.createSQLDeleteLeaderByName().contains(DEFAULT_TABLE));
        assertTrue(s.createSQLDeleteLeaderByName().endsWith(";"));
        assertTrue(s.createSQLDeleteLeaderByNameAndId().contains(DEFAULT_TABLE));
        assertTrue(s.createSQLDeleteLeaderByNameAndId().endsWith(";"));
        assertTrue(s.createSQLSelectLeaderByName().contains(DEFAULT_TABLE));
        assertTrue(s.createSQLSelectLeaderByName().endsWith(";"));
    }


    /**
     * Test JDBCLeaderElectionDatabaseStructure SQL — no trailing semicolons, standard varchar.
     */
    @Test
    public void testJDBCStructure() {
        JDBCLeaderElectionDatabaseStructure s = new JDBCLeaderElectionDatabaseStructure();

        assertTrue(s.createSQLTable().contains(DEFAULT_TABLE));
        assertTrue(s.createSQLTable().contains("varchar(36)"));
        assertTrue(!s.createSQLTable().endsWith(";"));
        assertTrue(s.createSQLIndex().contains(DEFAULT_TABLE));
        assertTrue(!s.createSQLIndex().endsWith(";"));
        assertTrue(s.createSQLDropTable().contains(DEFAULT_TABLE));
        assertTrue(!s.createSQLDropTable().endsWith(";"));
        assertTrue(s.createSQLInsertLeader().contains(DEFAULT_TABLE));
        assertTrue(!s.createSQLInsertLeader().endsWith(";"));
        assertTrue(s.createSQLDeleteLeaderByName().contains("WHERE name = ?"));
        assertTrue(s.createSQLDeleteLeaderByNameAndId().contains("WHERE name = ? AND id = ?"));
        assertTrue(s.createSQLSelectLeaderByName().contains("SELECT id, name"));
    }


    /**
     * Test MySQLLeaderElectionDatabaseStructure — no index (PRIMARY KEY creates it), constraint-based PK.
     */
    @Test
    public void testMySQLStructure() {
        MySQLLeaderElectionDatabaseStructure s = new MySQLLeaderElectionDatabaseStructure();

        assertTrue(s.createSQLTable().contains("CONSTRAINT pk_"));
        assertTrue(s.createSQLTable().contains("NOT NULL"));
        assertNull(s.createSQLIndex());
    }


    /**
     * Test MariaDBLeaderElectionDatabaseStructure — inherits from MySQL structure.
     */
    @Test
    public void testMariaDBStructure() {
        MariaDBLeaderElectionDatabaseStructure s = new MariaDBLeaderElectionDatabaseStructure();

        assertTrue(s.createSQLTable().contains("CONSTRAINT pk_"));
        assertNull(s.createSQLIndex());
    }


    /**
     * Test OracleLeaderElectionDatabaseStructure — VARCHAR2, no index.
     */
    @Test
    public void testOracleStructure() {
        OracleLeaderElectionDatabaseStructure s = new OracleLeaderElectionDatabaseStructure();

        assertTrue(s.createSQLTable().contains("VARCHAR2(36)"));
        assertTrue(s.createSQLTable().contains("CONSTRAINT pk_"));
        assertNull(s.createSQLIndex());
    }


    /**
     * Test SQLServerLeaderElectionDatabaseStructure — nvarchar type.
     */
    @Test
    public void testSQLServerStructure() {
        SQLServerLeaderElectionDatabaseStructure s = new SQLServerLeaderElectionDatabaseStructure();

        assertTrue(s.createSQLTable().contains(DEFAULT_TABLE));
        assertNull(s.createSQLIndex());
    }


    /**
     * Test that table name can be changed and all SQL reflects the new name.
     */
    @Test
    public void testCustomTableName() {
        JDBCLeaderElectionDatabaseStructure s = new JDBCLeaderElectionDatabaseStructure();
        s.setTableName("MyCustomTable");

        assertTrue(s.createSQLTable().contains("MyCustomTable"));
        assertTrue(s.createSQLIndex().contains("MyCustomTable"));
        assertTrue(s.createSQLDropTable().contains("MyCustomTable"));
        assertTrue(s.createSQLInsertLeader().contains("MyCustomTable"));
        assertTrue(s.createSQLDeleteLeaderByName().contains("MyCustomTable"));
        assertTrue(s.createSQLDeleteLeaderByNameAndId().contains("MyCustomTable"));
        assertTrue(s.createSQLSelectLeaderByName().contains("MyCustomTable"));
    }
}
