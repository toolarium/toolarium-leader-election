/*
 * LeaderElectionDAOTest.java
 *
 * Copyright by toolarium, all rights reserved.
 */
package com.github.toolarium.leader.election.impl.database.dao;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.github.toolarium.leader.election.dto.db.DatabaseLeaderElectionConfiguration;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


/**
 * Test the leader election dao.
 * 
 * @author patrick
 */
public class LeaderElectionDAOTest {
    private static final String MYTEST = "mytest";
    private static final String SA = "sa";
    private static final String USER = "user";
    private static final String STEAL_GROUP = "stealgroup";
    private static final Logger LOG = LoggerFactory.getLogger(LeaderElectionDAOTest.class);

    
    /**
     * Test
     *
     * @throws SQLException In case of a database error
     */
    @Test
    public void test() throws SQLException {
        
        JdbcDataSource ds = new JdbcDataSource();
        ds.setURL("jdbc:h2:mem:leaderelectiondao;DB_CLOSE_DELAY=-1");
        ds.setUser(SA);
        ds.setPassword("");
        DatabaseLeaderElectionConfiguration databaseLeaderElectionConfiguration = new DatabaseLeaderElectionConfiguration(2);
        databaseLeaderElectionConfiguration.setDataSource(ds);
        //LeaderElectionDatabaseStructure leaderElectionDatabaseStructure = databaseLeaderElectionConfiguration.getLeaderElectionDatabaseStructure();
        //leaderElectionDatabaseStructure.setTableName("TEST." + leaderElectionDatabaseStructure.getTableName());
                
        LeaderElectionDAO leaderElectionDAO = new LeaderElectionDAO(databaseLeaderElectionConfiguration);
        leaderElectionDAO.init();
        
        // insert and delete record
        LeaderElectorRecord leaderElectorRecord = new LeaderElectorRecord(MYTEST, "instance1", USER);
        leaderElectionDAO.insertLeader(leaderElectorRecord);
        assertEquals(1, leaderElectionDAO.deleteLeaderByName(MYTEST));

        // insert new leader and delete
        leaderElectorRecord = new LeaderElectorRecord(MYTEST, "instance1", USER);
        assertEquals(1, leaderElectionDAO.insertLeader(leaderElectorRecord));
        assertEquals(1, leaderElectionDAO.deleteLeaderByNameAndId(MYTEST, leaderElectorRecord.getId()));

        // insert new leader 
        leaderElectorRecord = new LeaderElectorRecord(MYTEST, "instance1", USER);
        assertEquals(1, leaderElectionDAO.insertLeader(leaderElectorRecord));
        
        // a second will fail
        assertEquals(-1, leaderElectionDAO.insertLeader(new LeaderElectorRecord(MYTEST, "instance1", USER))); // UC 

        // read and verify
        LeaderElectorRecord readLeaderElectorRecord = leaderElectionDAO.selectLeaderByName(MYTEST);
        LOG.debug("Read: " + leaderElectorRecord);
        assertEquals(leaderElectorRecord, readLeaderElectorRecord);

        assertNotNull(leaderElectorRecord);
    }


    /**
     * Verify that init() auto-renames the legacy {@code timestamp} column to {@code lease_ts}
     * and that the DAO works correctly after migration.
     *
     * @throws SQLException In case of a database error
     */
    @Test
    public void testSchemaMigration() throws SQLException {
        JdbcDataSource ds = new JdbcDataSource();
        ds.setURL("jdbc:h2:mem:leaderelectionmig;DB_CLOSE_DELAY=-1");
        ds.setUser(SA);
        ds.setPassword("");
        DatabaseLeaderElectionConfiguration config = new DatabaseLeaderElectionConfiguration(2);
        config.setDataSource(ds);

        // Create old schema: "timestamp" double-quoted (required in H2 — reserved keyword)
        try (Connection conn = ds.getConnection(); Statement stmt = conn.createStatement()) {
            stmt.execute("CREATE TABLE LeaderElection"
                    + "(id varchar(36) not NULL, name varchar(512) not NULL, "
                    + "instance varchar(255), username varchar(255), \"timestamp\" bigint not NULL, "
                    + "PRIMARY KEY (name))");
        }

        LeaderElectionDAO dao = new LeaderElectionDAO(config);
        dao.init(); // must migrate without error

        // Verify the migrated schema accepts inserts and selects
        LeaderElectorRecord rec = new LeaderElectorRecord(MYTEST, "instance1", USER);
        assertEquals(1, dao.insertLeader(rec));
        LeaderElectorRecord found = dao.selectLeaderByName(MYTEST);
        assertNotNull(found);
        assertEquals(rec, found);
    }


    /**
     * Verify that init() throws a descriptive {@link SQLException} when the table exists but
     * has missing required columns (schema mismatch).
     *
     * @throws SQLException In case of a database error
     */
    @Test
    public void testSchemaValidationError() throws SQLException {
        JdbcDataSource ds = new JdbcDataSource();
        ds.setURL("jdbc:h2:mem:leaderelectionbad;DB_CLOSE_DELAY=-1");
        ds.setUser(SA);
        ds.setPassword("");
        DatabaseLeaderElectionConfiguration config = new DatabaseLeaderElectionConfiguration(2);
        config.setDataSource(ds);

        // Create a table missing instance, username, and lease_ts
        try (Connection conn = ds.getConnection(); Statement stmt = conn.createStatement()) {
            stmt.execute("CREATE TABLE LeaderElection"
                    + "(id varchar(36) not NULL, name varchar(512) not NULL, "
                    + "junk varchar(255), PRIMARY KEY (name))");
        }

        LeaderElectionDAO dao = new LeaderElectionDAO(config);
        SQLException ex = assertThrows(SQLException.class, dao::init);
        assertTrue(ex.getMessage().contains("Schema mismatch"),
                "Error message must describe the mismatch, was: " + ex.getMessage());
        assertTrue(ex.getMessage().contains("LeaderElection"),
                "Error message must name the table, was: " + ex.getMessage());
    }


    /**
     * Verify stealLeadership succeeds on an expired lease, fails with an outdated id,
     * and fails when the lease is still fresh.
     *
     * @throws SQLException In case of a database error
     */
    @Test
    public void testStealLeadership() throws SQLException {
        JdbcDataSource ds = new JdbcDataSource();
        ds.setURL("jdbc:h2:mem:stealdao;DB_CLOSE_DELAY=-1");
        ds.setUser(SA);
        ds.setPassword("");
        DatabaseLeaderElectionConfiguration config = new DatabaseLeaderElectionConfiguration(2);
        config.setDataSource(ds);
        LeaderElectionDAO dao = new LeaderElectionDAO(config);
        dao.init();

        // Insert a leader whose lease expired 30 seconds ago
        long expiredTs = System.currentTimeMillis() - 30_000L;
        LeaderElectorRecord expiredLeader = new LeaderElectorRecord("uuid-old", STEAL_GROUP, "instance-A", USER, expiredTs);
        assertEquals(1, dao.insertLeader(expiredLeader));

        // Challenger steals the expired lease (threshold = 10 seconds ago, lease is 30s old → expired)
        long expiryThreshold = System.currentTimeMillis() - 10_000L;
        LeaderElectorRecord challenger = new LeaderElectorRecord(STEAL_GROUP, "instance-B", "user2");
        int stolen = dao.stealLeadership(STEAL_GROUP, "uuid-old", expiryThreshold, challenger);
        assertEquals(1, stolen, "Stealing an expired lease must succeed");

        // Verify the new leader record is stored
        LeaderElectorRecord current = dao.selectLeaderByName(STEAL_GROUP);
        assertNotNull(current);
        assertEquals("instance-B", current.getInstance());

        // Second steal attempt with the stale id — row id has changed, must return 0
        int secondSteal = dao.stealLeadership(STEAL_GROUP, "uuid-old", expiryThreshold,
                new LeaderElectorRecord(STEAL_GROUP, "instance-C", "user3"));
        assertEquals(0, secondSteal, "Stealing with an outdated id must fail");

        // Insert a fresh leader and attempt to steal a non-expired lease — must fail
        LeaderElectorRecord freshLeader = new LeaderElectorRecord("uuid-fresh", "freshgroup", "instance-A", USER, System.currentTimeMillis());
        assertEquals(1, dao.insertLeader(freshLeader));
        // expiryThreshold is in the past but the fresh lease is newer, so lease_ts > expiryThreshold
        int noSteal = dao.stealLeadership("freshgroup", "uuid-fresh", expiryThreshold,
                new LeaderElectorRecord("freshgroup", "instance-B", "user2"));
        assertEquals(0, noSteal, "Stealing a non-expired lease must fail");
    }


    /**
     * Verify getDatabaseCurrentTimeMillis returns a value within ±1 second of the local clock.
     *
     * @throws SQLException In case of a database error
     */
    @Test
    public void testGetDatabaseCurrentTimeMillis() throws SQLException {
        JdbcDataSource ds = new JdbcDataSource();
        ds.setURL("jdbc:h2:mem:dbtimestamp;DB_CLOSE_DELAY=-1");
        ds.setUser(SA);
        ds.setPassword("");
        DatabaseLeaderElectionConfiguration config = new DatabaseLeaderElectionConfiguration(2);
        config.setDataSource(ds);
        LeaderElectionDAO dao = new LeaderElectionDAO(config);

        long before = System.currentTimeMillis();
        long dbTime = dao.getDatabaseCurrentTimeMillis();
        long after = System.currentTimeMillis();

        assertTrue(dbTime >= before - 1000L && dbTime <= after + 1000L,
                "DB time must be within ±1 second of local clock: dbTime=" + dbTime
                + ", before=" + before + ", after=" + after);
    }


    /**
     * Verify migration of a v1.0.0-style table where the timestamp column was {@code varchar(25)}.
     * After the rename migration the DAO must complete an insert/select round-trip successfully.
     * In H2 the JDBC driver converts a Long value to its string representation when the target
     * column is VARCHAR; the DAO reads it back via toString/parseLong, so the round-trip works.
     *
     * @throws SQLException In case of a database error
     */
    @Test
    public void testVarcharTimestampMigration() throws SQLException {
        JdbcDataSource ds = new JdbcDataSource();
        ds.setURL("jdbc:h2:mem:varcharscmig;DB_CLOSE_DELAY=-1");
        ds.setUser(SA);
        ds.setPassword("");
        DatabaseLeaderElectionConfiguration config = new DatabaseLeaderElectionConfiguration(2);
        config.setDataSource(ds);

        // Create a v1.0.0-style table: timestamp is varchar(25), double-quoted for H2 reserved word
        try (Connection conn = ds.getConnection(); Statement stmt = conn.createStatement()) {
            stmt.execute("CREATE TABLE LeaderElection"
                    + "(id varchar(36) not NULL, name varchar(512) not NULL, "
                    + "instance varchar(255), username varchar(255), \"timestamp\" varchar(25) not NULL, "
                    + "PRIMARY KEY (name))");
        }

        LeaderElectionDAO dao = new LeaderElectionDAO(config);
        dao.init(); // must rename column without error

        // After migration the column is lease_ts varchar(25) in H2; setObject(Long) stores as string.
        LeaderElectorRecord rec = new LeaderElectorRecord("mig-group", "inst-1", System.getProperty("user.name"));
        assertEquals(1, dao.insertLeader(rec));
        LeaderElectorRecord found = dao.selectLeaderByName("mig-group");
        assertNotNull(found);
        assertEquals(rec.getInstance(), found.getInstance());
        assertTrue(found.getTimestamp() > 0, "Timestamp must be a positive epoch-millis value after migration");
    }
}
