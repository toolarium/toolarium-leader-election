/*
 * TableLookupTest.java
 *
 * Copyright by toolarium, all rights reserved.
 */
package com.github.toolarium.leader.election.impl.database.dao;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.github.toolarium.leader.election.dto.db.DatabaseLeaderElectionConfiguration;
import com.github.toolarium.leader.election.dto.db.JDBCLeaderElectionDatabaseStructure;
import com.github.toolarium.leader.election.dto.db.MariaDBLeaderElectionDatabaseStructure;
import com.github.toolarium.leader.election.dto.db.MySQLLeaderElectionDatabaseStructure;
import com.github.toolarium.leader.election.dto.db.OracleLeaderElectionDatabaseStructure;
import com.github.toolarium.leader.election.dto.db.SQLServerLeaderElectionDatabaseStructure;
import java.sql.Connection;
import java.sql.Statement;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;


/**
 * Tests the lookup of the leader election table in the metadata catalog: schema-qualified names and LIKE wildcards.
 *
 * @author patrick
 */
public class TableLookupTest {

    /**
     * A schema-qualified table name is found in its schema: a second elector with DDL disabled accepts the existing table.
     *
     * @throws Exception in case of an error
     */
    @Test
    public void testSchemaQualifiedTableIsFound() throws Exception {
        JdbcDataSource ds = createDataSource("tablelookupschema");
        try (Connection conn = ds.getConnection(); Statement stmt = conn.createStatement()) {
            stmt.execute("CREATE SCHEMA IF NOT EXISTS le_schema");
        }

        new LeaderElectionDAO(createConfiguration(ds, "le_schema.leader_election", true)).init();

        LeaderElectionDAO dao = new LeaderElectionDAO(createConfiguration(ds, "le_schema.leader_election", false));
        dao.init();
        try (Connection conn = ds.getConnection()) {
            assertTrue(dao.existDatabaseTable(conn), "The table must be found in its schema");
            assertTrue(dao.getExistingColumns(conn).contains("lease_ts"));
        }
    }


    /**
     * The table name is no LIKE pattern: an underscore does not match another character of another table.
     *
     * @throws Exception in case of an error
     */
    @Test
    public void testUnderscoreDoesNotMatchOtherTable() throws Exception {
        JdbcDataSource ds = createDataSource("tablelookupwildcard");
        try (Connection conn = ds.getConnection(); Statement stmt = conn.createStatement()) {
            stmt.execute("CREATE TABLE IF NOT EXISTS leaderXelection (other_column INT)");
        }

        LeaderElectionDAO dao = new LeaderElectionDAO(createConfiguration(ds, "leader_election", false));
        try (Connection conn = ds.getConnection()) {
            assertFalse(dao.existDatabaseTable(conn), "Another table must not match the underscore");
            assertTrue(dao.getExistingColumns(conn).isEmpty());
        }
    }


    /**
     * Without a configured structure, the structure of the database is detected by the product name of the driver: MariaDB
     * before MySQL (a MariaDB driver may report both), and the standard structure for others (e.g. PostgreSQL, H2).
     */
    @Test
    public void testStructureIsDetectedByProductName() {
        DatabaseLeaderElectionConfiguration config = new DatabaseLeaderElectionConfiguration();
        config.setDataSource(createDataSource("tablelookupdetect"));
        LeaderElectionDAO dao = new LeaderElectionDAO(config);
        assertEquals(OracleLeaderElectionDatabaseStructure.class, dao.detectDatabaseStructure("Oracle").getClass());
        assertEquals(MariaDBLeaderElectionDatabaseStructure.class, dao.detectDatabaseStructure("MariaDB").getClass());
        assertEquals(MariaDBLeaderElectionDatabaseStructure.class, dao.detectDatabaseStructure("MySQL compatible MariaDB").getClass());
        assertEquals(MySQLLeaderElectionDatabaseStructure.class, dao.detectDatabaseStructure("MySQL").getClass());
        assertEquals(SQLServerLeaderElectionDatabaseStructure.class, dao.detectDatabaseStructure("Microsoft SQL Server").getClass());
        assertEquals(JDBCLeaderElectionDatabaseStructure.class, dao.detectDatabaseStructure("PostgreSQL").getClass());
        assertEquals(JDBCLeaderElectionDatabaseStructure.class, dao.detectDatabaseStructure("H2").getClass());
    }


    /**
     * The LIKE wildcards and the escape itself are escaped.
     */
    @Test
    public void testEscapePattern() {
        assertEquals("leader\\_election", LeaderElectionDAO.escapePattern("leader_election", "\\"));
        assertEquals("a\\%b\\\\c", LeaderElectionDAO.escapePattern("a%b\\c", "\\"));
        assertEquals("leader_election", LeaderElectionDAO.escapePattern("leader_election", ""));
        assertNull(LeaderElectionDAO.escapePattern(null, "\\"));
    }


    /**
     * Create an in-memory data source.
     *
     * @param name the database name
     * @return the data source
     */
    private static JdbcDataSource createDataSource(String name) {
        JdbcDataSource ds = new JdbcDataSource();
        ds.setURL("jdbc:h2:mem:" + name + ";DB_CLOSE_DELAY=-1");
        ds.setUser("sa");
        ds.setPassword("");
        return ds;
    }


    /**
     * Create a configuration with a table name.
     *
     * @param ds the data source
     * @param tableName the table name
     * @param allowDdl true to allow the creation of the table
     * @return the configuration
     */
    private static DatabaseLeaderElectionConfiguration createConfiguration(JdbcDataSource ds, String tableName, boolean allowDdl) {
        JDBCLeaderElectionDatabaseStructure structure = new JDBCLeaderElectionDatabaseStructure();
        structure.setTableName(tableName);
        DatabaseLeaderElectionConfiguration config = new DatabaseLeaderElectionConfiguration();
        config.setDataSource(ds);
        config.setLeaderElectionDatabaseStructure(structure);
        config.setAllowDdl(allowDdl);
        return config;
    }
}
