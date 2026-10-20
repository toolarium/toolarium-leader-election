/*
 * DatabaseElectionCompletionTest.java
 *
 * Copyright by toolarium, all rights reserved.
 */
package com.github.toolarium.leader.election.impl.database;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.github.toolarium.leader.election.ILeaderElector;
import com.github.toolarium.leader.election.LeaderElectionFactory;
import com.github.toolarium.leader.election.dto.LeaderElectionInformation;
import com.github.toolarium.leader.election.dto.LeaderElectionStrategy;
import com.github.toolarium.leader.election.dto.db.DatabaseLeaderElectionConfiguration;
import java.time.Duration;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;


/**
 * Tests the synchronous-first-round behaviour of the DATABASE strategy using an in-memory H2 database.
 * Because the first election round runs synchronously inside {@code elect()}, {@link ILeaderElector#isLeader()}
 * is accurate the moment the call returns — no additional wait is needed.
 *
 * @author patrick
 */
public class DatabaseElectionCompletionTest {


    /**
     * Create a leader elector backed by an in-memory H2 database.
     *
     * @param dbName the H2 database name (unique per test)
     * @param groupName the election group name
     * @return the leader elector
     * @throws Exception in case of an error
     */
    private ILeaderElector createElector(String dbName, String groupName) throws Exception {
        JdbcDataSource ds = new JdbcDataSource();
        ds.setURL("jdbc:h2:mem:" + dbName + ";DB_CLOSE_DELAY=-1");
        ds.setUser("sa");
        ds.setPassword("");
        DatabaseLeaderElectionConfiguration config = new DatabaseLeaderElectionConfiguration(2);
        config.setDataSource(ds);
        return LeaderElectionFactory.getInstance().getLeaderElection(
                LeaderElectionStrategy.DATABASE, new LeaderElectionInformation(groupName), config);
    }


    /**
     * A single instance must be leader immediately when elect() returns — no additional wait.
     * This verifies the synchronous first-round behaviour.
     *
     * @throws Exception In case of an error
     */
    @Test
    public void testSingleInstanceIsLeaderImmediatelyAfterElect() throws Exception {
        ILeaderElector elector = createElector("compl_single", "compl-single");
        assertFalse(elector.isLeader());
        assertTrue(elector.elect());
        assertTrue(elector.isLeader(), "Single instance must be leader right after elect()");
        elector.close();
    }


    /**
     * When two instances share the same database, exactly one must hold leadership
     * immediately after both elect() calls return — no additional wait needed.
     *
     * @throws Exception In case of an error
     */
    @Test
    public void testTwoInstancesExactlyOneLeaderAfterBothElect() throws Exception {
        JdbcDataSource ds = new JdbcDataSource();
        ds.setURL("jdbc:h2:mem:compl_two;DB_CLOSE_DELAY=-1");
        ds.setUser("sa");
        ds.setPassword("");

        DatabaseLeaderElectionConfiguration configA = new DatabaseLeaderElectionConfiguration(2);
        configA.setDataSource(ds);
        DatabaseLeaderElectionConfiguration configB = new DatabaseLeaderElectionConfiguration(2);
        configB.setDataSource(ds);

        ILeaderElector electorA = LeaderElectionFactory.getInstance().getLeaderElection(
                LeaderElectionStrategy.DATABASE, new LeaderElectionInformation("two-node"), configA);
        ILeaderElector electorB = LeaderElectionFactory.getInstance().getLeaderElection(
                LeaderElectionStrategy.DATABASE, new LeaderElectionInformation("two-node"), configB);

        boolean resultA = electorA.elect();
        boolean resultB = electorB.elect();
        assertTrue(resultA ^ resultB, "Exactly one of A or B must be leader, was: A=" + resultA + " B=" + resultB);

        electorA.close();
        electorB.close();
    }


    /**
     * elect(Duration.ZERO) must return true for DATABASE because the first round runs synchronously
     * inside elect() — the latch is already counted down before await(0) is called.
     *
     * @throws Exception In case of an error
     */
    @Test
    public void testElectZeroTimeout() throws Exception {
        ILeaderElector elector = createElector("compl_zero", "compl-zero");
        assertTrue(elector.elect(Duration.ZERO),
                "DATABASE elect(ZERO) must return true — first round is synchronous");
        assertTrue(elector.isLeader());
        elector.close();
    }


    /**
     * elect() with a tight timeout still returns immediately for DATABASE because the first round
     * is synchronous. elect() called a second time is idempotent and returns the current state.
     *
     * @throws Exception In case of an error
     */
    @Test
    public void testElectIdempotent() throws Exception {
        ILeaderElector elector = createElector("compl_idem", "compl-idem");
        assertTrue(elector.elect(Duration.ofMillis(500)));
        assertTrue(elector.elect(Duration.ofMillis(100)), "Second elect() must return same leader state");
        elector.close();
        assertFalse(elector.isLeader());
    }
}
