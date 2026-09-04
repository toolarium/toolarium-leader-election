/*
 * AbstractLeaderElectorTest.java
 *
 * Copyright by toolarium, all rights reserved.
 */
package com.github.toolarium.leader.election;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


/**
 * Abstract base test for all leader election strategies.
 * Subclasses implement {@link #createLeaderElector(String)} to provide
 * a strategy-specific instance; the test logic is identical for all backends.
 *
 * @author patrick
 */
public abstract class AbstractLeaderElectorTest {
    private static final Logger LOG = LoggerFactory.getLogger(AbstractLeaderElectorTest.class);


    /**
     * Create a leader elector for the given resource name.
     * Two calls with the same name must compete for the same resource.
     *
     * @param name the resource name
     * @return the leader elector
     * @throws Exception In case of an error
     */
    protected abstract LeaderElector createLeaderElector(String name) throws Exception;


    /**
     * Maximum milliseconds to wait for a node to establish leadership after initialize().
     * Depends on the backend's scheduler tick interval and cluster join time.
     * Override in subclasses with slow or late-joining backends (e.g. JGroups, Oracle XE).
     *
     * @return the max wait in milliseconds (default 1500)
     */
    protected long getInitWaitMillis() {
        return 1500L;
    }


    /**
     * Maximum milliseconds to wait for the standby node to take over after the leader closes.
     * Depends on the backend's scheduler tick interval and graceful close latency.
     * Override in subclasses with slow backends.
     *
     * @return the max wait in milliseconds (default 2000)
     */
    protected long getFailoverWaitMillis() {
        return 2000L;
    }


    /**
     * Test simple leader initialization: create, initialize (idempotent), verify leadership, close (idempotent).
     *
     * @throws Exception In case of an error
     */
    @Test
    public void testInitialize() throws Exception {
        LOG.debug("->Get leader");
        LeaderElector leaderElector = createLeaderElector("testInitialize");
        LOG.debug("->Check leader");
        assertFalse(leaderElector.isLeader());
        Thread.sleep(50L);
        LOG.debug("->Check leader");
        assertFalse(leaderElector.isLeader());
        LOG.debug("->Initialize leader");
        leaderElector.initialize();
        Thread.sleep(50L);
        LOG.debug("->Initialize leader (idempotent)");
        leaderElector.initialize();
        awaitCondition(() -> leaderElector.isLeader(), getInitWaitMillis());
        LOG.debug("->Check leader");
        assertTrue(leaderElector.isLeader());
        LOG.debug("->Close leader");
        leaderElector.close();
        LOG.debug("->Close leader (idempotent)");
        leaderElector.close();
        LOG.debug("->Check leader");
        assertFalse(leaderElector.isLeader());
    }


    /**
     * Test leader failover: two competing instances, leader A holds until closed, then B takes over.
     *
     * @throws Exception In case of an error
     */
    @Test
    public void testMultipleLeaders() throws Exception {
        LeaderElector leaderElectorA = createLeaderElector("testMultipleLeaders");
        leaderElectorA.initialize();

        awaitCondition(() -> leaderElectorA.isLeader(), getInitWaitMillis());
        assertTrue(leaderElectorA.isLeader());

        LeaderElector leaderElectorB = createLeaderElector("testMultipleLeaders");
        leaderElectorB.initialize();

        assertFalse(leaderElectorB.isLeader());
        Thread.sleep(50L);
        assertTrue(leaderElectorA.isLeader());

        Thread.sleep(500L);
        assertTrue(leaderElectorA.isLeader());
        assertFalse(leaderElectorB.isLeader());
        Thread.sleep(500L);
        assertTrue(leaderElectorA.isLeader());
        assertFalse(leaderElectorB.isLeader());

        leaderElectorA.close();
        Thread.sleep(200L);
        assertFalse(leaderElectorA.isLeader());
        awaitCondition(() -> leaderElectorB.isLeader(), getFailoverWaitMillis());
        assertTrue(leaderElectorB.isLeader());

        Thread.sleep(50L);
        assertTrue(leaderElectorB.isLeader());
        leaderElectorB.close();
    }


    /**
     * Poll {@code condition} every 50 ms until it returns {@code true} or {@code timeoutMillis} elapses.
     *
     * @param condition the condition to poll
     * @param timeoutMillis maximum time to wait in milliseconds
     * @throws InterruptedException if the thread is interrupted while sleeping
     */
    protected void awaitCondition(BooleanSupplier condition, long timeoutMillis) throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMillis;
        while (!condition.getAsBoolean() && System.currentTimeMillis() < deadline) {
            Thread.sleep(50L);
        }
    }
}
