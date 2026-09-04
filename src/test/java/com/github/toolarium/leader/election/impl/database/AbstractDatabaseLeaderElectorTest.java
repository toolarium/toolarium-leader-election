/*
 * AbstractDatabaseLeaderElectorTest.java
 *
 * Copyright by toolarium, all rights reserved.
 */
package com.github.toolarium.leader.election.impl.database;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.github.toolarium.leader.election.AbstractLeaderElectorTest;
import com.github.toolarium.leader.election.LeaderElectionFactory;
import com.github.toolarium.leader.election.LeaderElectionStrategy;
import com.github.toolarium.leader.election.LeaderElector;
import com.github.toolarium.leader.election.dto.LeaderElectionInformation;
import com.github.toolarium.leader.election.dto.db.DatabaseLeaderElectionConfiguration;
import com.github.toolarium.leader.election.dto.db.JDBCLeaderElectionDatabaseStructure;
import com.github.toolarium.leader.election.impl.database.dao.LeaderElectionDAO;
import com.github.toolarium.leader.election.impl.database.dao.LeaderElectorRecord;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import org.junit.jupiter.api.Test;


/**
 * Abstract base test for database-backed leader election.
 * Subclasses implement {@link #createConfiguration()} to supply the JDBC connection
 * and database structure; all test logic is inherited from {@link AbstractLeaderElectorTest}.
 *
 * @author patrick
 */
public abstract class AbstractDatabaseLeaderElectorTest extends AbstractLeaderElectorTest {


    /**
     * Create a fully configured {@link DatabaseLeaderElectionConfiguration} for this backend.
     *
     * @return the database leader election configuration
     * @throws Exception In case of an error
     */
    protected abstract DatabaseLeaderElectionConfiguration createConfiguration() throws Exception;


    /**
     * @see com.github.toolarium.leader.election.AbstractLeaderElectorTest#createLeaderElector(java.lang.String)
     */
    @Override
    protected LeaderElector createLeaderElector(String name) throws Exception {
        return LeaderElectionFactory.getInstance().getLeaderElection(
                LeaderElectionStrategy.DATABASE, new LeaderElectionInformation(name), createConfiguration());
    }


    /**
     * Verify that {@code init()} auto-migrates the legacy {@code timestamp} column to
     * {@code lease_ts} and that leadership election works correctly after migration.
     *
     * @throws Exception In case of an error
     */
    @Test
    public void testSchemaMigration() throws Exception {
        DatabaseLeaderElectionConfiguration config = createConfiguration();
        if (config.getLeaderElectionDatabaseStructure() == null) {
            config.setLeaderElectionDatabaseStructure(new JDBCLeaderElectionDatabaseStructure());
        }

        String migTable = "LeaderElectionMig";
        config.getLeaderElectionDatabaseStructure().setTableName(migTable);

        // Create old schema: same columns but 'timestamp' instead of 'lease_ts'
        try (Connection conn = config.getDataSource().getConnection();
             Statement stmt = conn.createStatement()) {
            stmt.execute(legacyTableDDL(migTable));
        }

        // init() must auto-migrate without throwing
        LeaderElectionDAO dao = new LeaderElectionDAO(config);
        dao.init();

        // Post-migration: election must work using the renamed column
        String electionName = "mig-group";
        LeaderElectorRecord rec = new LeaderElectorRecord(electionName, "inst-1", System.getProperty("user.name"));
        assertEquals(1, dao.insertLeader(rec));
        LeaderElectorRecord found = dao.selectLeaderByName(electionName);
        assertNotNull(found);
        assertEquals(rec, found);

        // Cleanup
        try (Connection conn = config.getDataSource().getConnection();
             Statement stmt = conn.createStatement()) {
            stmt.execute("DROP TABLE " + migTable);
        }
    }


    /**
     * Verify that {@code init()} throws a descriptive {@link SQLException} when the table
     * exists but has a mismatched schema (missing required columns).
     *
     * @throws Exception In case of an error
     */
    @Test
    public void testSchemaValidationError() throws Exception {
        DatabaseLeaderElectionConfiguration config = createConfiguration();
        if (config.getLeaderElectionDatabaseStructure() == null) {
            config.setLeaderElectionDatabaseStructure(new JDBCLeaderElectionDatabaseStructure());
        }

        String badTable = "LeaderElectionBad";
        config.getLeaderElectionDatabaseStructure().setTableName(badTable);

        // Create a table with wrong columns (missing instance, username, lease_ts)
        try (Connection conn = config.getDataSource().getConnection();
             Statement stmt = conn.createStatement()) {
            stmt.execute(mismatchedTableDDL(badTable));
        }

        LeaderElectionDAO dao = new LeaderElectionDAO(config);
        try {
            SQLException ex = assertThrows(SQLException.class, dao::init);
            assertTrue(ex.getMessage().contains("Schema mismatch"),
                    "Error must describe mismatch, was: " + ex.getMessage());
            assertTrue(ex.getMessage().contains(badTable),
                    "Error must name the table, was: " + ex.getMessage());
        } finally {
            // Always drop the bad table so it does not affect other tests
            try (Connection conn = config.getDataSource().getConnection();
                 Statement stmt = conn.createStatement()) {
                stmt.execute("DROP TABLE " + badTable);
            }
        }
    }


    /**
     * Return the CREATE TABLE DDL for the legacy schema where the numeric timestamp column
     * is named {@code timestamp} (the old column name before the {@code lease_ts} rename).
     * Override in dialect-specific tests when the default ANSI SQL syntax is not supported.
     *
     * @param tableName the table name to use
     * @return DDL string (no trailing semicolon)
     */
    protected String legacyTableDDL(String tableName) {
        // Double-quote "timestamp" — required in H2 (reserved keyword) and exact in PostgreSQL
        return "CREATE TABLE " + tableName
                + "(id varchar(36) not NULL, name varchar(512) not NULL, "
                + "instance varchar(255), username varchar(255), \"timestamp\" bigint not NULL, "
                + "PRIMARY KEY (name))";
    }


    /**
     * Return the CREATE TABLE DDL for a schema that is missing required columns.
     * Override in dialect-specific tests when the default ANSI SQL syntax is not supported.
     *
     * @param tableName the table name to use
     * @return DDL string (no trailing semicolon)
     */
    protected String mismatchedTableDDL(String tableName) {
        return "CREATE TABLE " + tableName
                + "(id varchar(36) not NULL, name varchar(512) not NULL, "
                + "junk varchar(255), PRIMARY KEY (name))";
    }
}
