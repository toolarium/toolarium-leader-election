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
 * Tests the elect() completion behaviour of the DATABASE strategy using an in-memory H2 database.
 * The first election round runs asynchronously in the scheduler thread; {@code elect()} blocks until
 * the latch is counted down by the first tick, so {@link ILeaderElector#isLeader()} is accurate
 * as soon as the call returns (within the given timeout).
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
     * elect(short timeout) must return true for DATABASE because the first tick fires with
     * initialDelay=0 and an in-memory H2 round-trip completes well within 500 ms.
     *
     * @throws Exception In case of an error
     */
    @Test
    public void testElectZeroTimeout() throws Exception {
        ILeaderElector elector = createElector("compl_zero", "compl-zero");
        assertTrue(elector.elect(Duration.ofMillis(500)),
                "DATABASE elect() must return true — first tick fires immediately (initialDelay=0)");
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
