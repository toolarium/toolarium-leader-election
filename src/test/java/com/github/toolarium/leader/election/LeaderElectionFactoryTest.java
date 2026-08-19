/*
 * LeaderElectionFactoryTest.java
 *
 * Copyright by toolarium, all rights reserved.
 */
package com.github.toolarium.leader.election;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.github.toolarium.leader.election.dto.LeaderElectionConfiguration;
import com.github.toolarium.leader.election.dto.LeaderElectionInformation;
import com.github.toolarium.leader.election.exception.LeaderElectionException;
import com.github.toolarium.leader.election.impl.kubernetes.KubernetesUtil;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Test
 *
 * @author patrick
 */
public class LeaderElectionFactoryTest {
    private static final Logger LOG = LoggerFactory.getLogger(LeaderElectionFactoryTest.class);


    /**
     * Tear down the test — reset singleton state modified during tests.
     */
    @AfterEach
    public void tearDown() {
        KubernetesUtil.getInstance().setCheckEnvironmentVariables(true);
        KubernetesUtil.getInstance().setCheckEndpoint(true);
    }


    /**
     * Test
     *
     * @throws Exception In case of an error
     */
    @Test
    public void test() throws Exception {
        verifyInitialize(LeaderElectionStrategy.FILE, new LeaderElectionInformation("testFile"), new LeaderElectionConfiguration(2));
        testMultipleLeaders(LeaderElectionStrategy.FILE, new LeaderElectionInformation("testFile"), new LeaderElectionConfiguration(2));

        verifyInitialize(LeaderElectionStrategy.NETWORK, new LeaderElectionInformation("testFile"), new LeaderElectionConfiguration(2));
        testMultipleLeaders(LeaderElectionStrategy.NETWORK, new LeaderElectionInformation("testFile"), new LeaderElectionConfiguration(2));
    }

    /**
     * Test the file strategy
     *
     * @throws Exception In case of an error
     */
    @Test
    public void testFile() throws Exception {

        LeaderElector leaderElectorA = LeaderElectionFactory.getInstance().getLeaderElection(LeaderElectionStrategy.FILE, new LeaderElectionInformation("testFile"), new LeaderElectionConfiguration(2));
        assertFalse(leaderElectorA.isLeader());
        Thread.sleep(50);
        assertFalse(leaderElectorA.isLeader());
        leaderElectorA.initialize();
        awaitCondition(() -> leaderElectorA.isLeader(), 200);
        assertTrue(leaderElectorA.isLeader());
        leaderElectorA.close();
        assertFalse(leaderElectorA.isLeader());
    }

    /**
     * Test the network strategy
     *
     * @throws Exception In case of an error
     */
    @Test
    public void testNetwork() throws Exception {

        LeaderElector leaderElectorA = LeaderElectionFactory.getInstance().getLeaderElection(LeaderElectionStrategy.NETWORK, new LeaderElectionInformation("testNetwork"), new LeaderElectionConfiguration(2));
        assertFalse(leaderElectorA.isLeader());
        Thread.sleep(50);
        assertFalse(leaderElectorA.isLeader());
        leaderElectorA.initialize();
        awaitCondition(() -> leaderElectorA.isLeader(), 2000);
        assertTrue(leaderElectorA.isLeader());
        leaderElectorA.close();
        assertFalse(leaderElectorA.isLeader());
    }


    /**
     * Test the kubernetes
     *
     * @throws Exception In case of an error
     */
    @Test
    public void testKubernetes() throws Exception {

        KubernetesUtil.getInstance().setCheckEnvironmentVariables(false);
        //KubernetesUtil.getInstance().setCheckEndpoint(false);

        LeaderElector el = LeaderElectionFactory.getInstance().getLeaderElection(new LeaderElectionInformation("namespace", "name", "test"), new LeaderElectionConfiguration(2));
        el.initialize();
        assertFalse(el.isLeader());
        awaitCondition(() -> el.isLeader(), 3000);

        assertTrue(el.isLeader());
        Thread.sleep(500);
        assertTrue(el.isLeader());
        Thread.sleep(500);
        assertTrue(el.isLeader());
        el.close();
    }


    /**
     * Test that DATABASE strategy with a non-DatabaseLeaderElectionConfiguration throws LeaderElectionException.
     */
    @Test
    public void testDatabaseStrategyRequiresDatabaseConfiguration() {
        assertThrows(LeaderElectionException.class, () ->
            LeaderElectionFactory.getInstance().getLeaderElection(
                    LeaderElectionStrategy.DATABASE,
                    new LeaderElectionInformation("testDb"),
                    new LeaderElectionConfiguration(2)));
    }


    /**
     * Verify simple leader initialization
     *
     * @param strategy the leader election strategy. To choose automated put in null.
     * @param leaderElectionInformation the leader election information
     * @param leaderElectionConfiguration the leader election configuration
     * @throws Exception In case of an error
     */
    protected void verifyInitialize(LeaderElectionStrategy strategy, LeaderElectionInformation leaderElectionInformation, LeaderElectionConfiguration leaderElectionConfiguration) throws Exception {
        LOG.debug("->Get leader");
        LeaderElector leaderElector = LeaderElectionFactory.getInstance().getLeaderElection(strategy, leaderElectionInformation, leaderElectionConfiguration);
        LOG.debug("->Check leader");
        assertFalse(leaderElector.isLeader());
        Thread.sleep(50);
        LOG.debug("->Check leader");
        assertFalse(leaderElector.isLeader());
        LOG.debug("->Initilize leader");
        leaderElector.initialize();
        Thread.sleep(50);
        LOG.debug("->Initilize leader");
        leaderElector.initialize();
        awaitCondition(() -> leaderElector.isLeader(), 3000);
        LOG.debug("->Check leader");
        assertTrue(leaderElector.isLeader());
        LOG.debug("->Close leader");
        leaderElector.close();
        LOG.debug("->Close leader");
        leaderElector.close();
        LOG.debug("->Check leader");
        assertFalse(leaderElector.isLeader());
    }


    /**
     * Test multiple leaders
     *
     * @param strategy the leader election strategy. To choose automated put in null.
     * @param leaderElectionInformation the leader election information
     * @param leaderElectionConfiguration the leader election configuration
     * @throws Exception In case of an error
     */
    protected void testMultipleLeaders(LeaderElectionStrategy strategy, LeaderElectionInformation leaderElectionInformation, LeaderElectionConfiguration leaderElectionConfiguration) throws Exception {

        LeaderElector leaderElectorA = LeaderElectionFactory.getInstance().getLeaderElection(strategy, leaderElectionInformation, leaderElectionConfiguration);
        leaderElectorA.initialize();

        awaitCondition(() -> leaderElectorA.isLeader(), 3000);
        assertTrue(leaderElectorA.isLeader());

        // create second instance
        LeaderElector leaderElectorB = LeaderElectionFactory.getInstance().getLeaderElection(strategy, leaderElectionInformation, leaderElectionConfiguration);
        leaderElectorB.initialize();

        assertFalse(leaderElectorB.isLeader());
        awaitCondition(() -> leaderElectorA.isLeader(), 1500);
        assertTrue(leaderElectorA.isLeader());

        Thread.sleep(500);
        assertTrue(leaderElectorA.isLeader());
        assertFalse(leaderElectorB.isLeader());
        Thread.sleep(500);
        assertTrue(leaderElectorA.isLeader());
        assertFalse(leaderElectorB.isLeader());

        leaderElectorA.close();
        Thread.sleep(200);
        assertFalse(leaderElectorA.isLeader());
        awaitCondition(() -> leaderElectorB.isLeader(), 6000);
        assertTrue(leaderElectorB.isLeader());

        Thread.sleep(50);
        assertTrue(leaderElectorB.isLeader());
        leaderElectorB.close();
    }


    /**
     * Poll the given condition every 50 ms until it returns {@code true} or the timeout elapses.
     * The caller is responsible for asserting the final state after this method returns.
     *
     * @param condition the condition to poll
     * @param timeoutMillis maximum time to wait in milliseconds
     * @throws InterruptedException if the polling sleep is interrupted
     */
    private void awaitCondition(BooleanSupplier condition, long timeoutMillis) throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMillis;
        while (!condition.getAsBoolean() && System.currentTimeMillis() < deadline) {
            Thread.sleep(50L);
        }
    }
}
