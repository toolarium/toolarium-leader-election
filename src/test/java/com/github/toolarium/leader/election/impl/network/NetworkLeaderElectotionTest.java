/*
 * NetworkLeaderElectotionTest.java
 *
 * Copyright by toolarium, all rights reserved.
 */
package com.github.toolarium.leader.election.impl.network;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.github.toolarium.leader.election.AbstractLeaderElectorTest;
import com.github.toolarium.leader.election.ILeaderElector;
import com.github.toolarium.leader.election.ILeaderLease;
import com.github.toolarium.leader.election.LeaderElectionFactory;
import com.github.toolarium.leader.election.dto.LeaderElectionConfiguration;
import com.github.toolarium.leader.election.dto.LeaderElectionInformation;
import com.github.toolarium.leader.election.dto.LeaderElectionStrategy;
import com.github.toolarium.leader.election.exception.LeaderElectionException;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;


/**
 * Implements leader election tests using the network strategy.
 *
 * @author patrick
 */
public class NetworkLeaderElectotionTest extends AbstractLeaderElectorTest {


    /**
     * @see com.github.toolarium.leader.election.AbstractLeaderElectorTest#getInitWaitMillis()
     */
    @Override
    protected long getInitWaitMillis() {
        return 8000L;
    }


    /**
     * @see com.github.toolarium.leader.election.AbstractLeaderElectorTest#getFailoverWaitMillis()
     */
    @Override
    protected long getFailoverWaitMillis() {
        return 12000L;
    }


    /**
     * @see com.github.toolarium.leader.election.AbstractLeaderElectorTest#createLeaderElector(java.lang.String)
     */
    @Override
    protected ILeaderElector createLeaderElector(String name) throws Exception {
        return LeaderElectionFactory.getInstance().getLeaderElection(
                LeaderElectionStrategy.NETWORK, new LeaderElectionInformation(name), new LeaderElectionConfiguration(10));
    }


    /**
     * Overrides the base test because {@code elect(null)} delegates to the no-timeout {@code elect()},
     * which blocks on the election latch indefinitely if JGroups has not yet fired a view.
     * This override establishes leadership with a bounded timeout first so that the
     * {@code elect(null)} call is idempotent and returns the already-known state immediately.
     *
     * @throws Exception In case of an error
     */
    @Test
    @Override
    public void testElectWithNullTimeout() throws Exception {
        ILeaderElector elector = createLeaderElector("testNullTimeout");
        assertTrue(elector.elect(Duration.ofMillis(getInitWaitMillis())), "Must become leader on first elect()");
        assertTrue(elector.elect((Duration) null), "elect(null) on established elector must return true immediately");
        assertTrue(elector.isLeader());
        elector.close();
    }


    /**
     * Overrides the base test because JGroups UDP multicast congestion from prior tests
     * can delay leadership establishment beyond the default timeout. This override uses
     * the failover wait window to absorb the JGroups stabilisation delay.
     *
     * @throws Exception In case of an error
     */
    @Test
    @Override
    public void testRunIfLeaderInterruptedOnLeadershipLoss() throws Exception {
        ILeaderElector leaderElector = createLeaderElector("testInterruption");
        assertTrue(leaderElector.elect(Duration.ofMillis(getFailoverWaitMillis())),
                "Must become leader within failover window");

        CountDownLatch taskStarted = new CountDownLatch(1);
        AtomicBoolean taskWasInterrupted = new AtomicBoolean(false);
        AtomicReference<ILeaderLease> capturedLease = new AtomicReference<>();

        Thread taskThread = new Thread(() -> {
            try {
                leaderElector.runIfLeader("blocking-task", lease -> {
                    capturedLease.set(lease);
                    taskStarted.countDown();
                    try {
                        Thread.sleep(30_000L);
                    } catch (InterruptedException e) {
                        taskWasInterrupted.set(true);
                        Thread.currentThread().interrupt();
                    }
                });
            } catch (Exception e) {
                // ignored
            }
        });
        taskThread.start();

        assertTrue(taskStarted.await(2000L, TimeUnit.MILLISECONDS), "Task must start within 2s");

        leaderElector.close();

        taskThread.join(3000L);
        assertFalse(taskThread.isAlive(), "Task thread must finish within 3s after close");
        assertTrue(taskWasInterrupted.get(), "Task thread must have been interrupted");

        ILeaderLease lease = capturedLease.get();
        assertNotNull(lease);
        assertFalse(lease.isValid(), "Lease must be invalid after elector is closed");
        assertThrows(LeaderElectionException.class, lease::verify, "verify() must throw LeaderElectionException when lease is invalid");
    }


    /**
     * Overrides the base test to insert a stabilisation pause between the two lifecycles.
     * After a JGroups channel is closed, the underlying UDP socket and cluster membership
     * state need a moment to settle before a new channel connects to the same cluster name.
     * Without the pause, the second {@code elect()} can race with residual JGroups state
     * and time out before the phantom-member stabilisation window (retryPeriod + 2 s) elapses.
     *
     * @throws Exception In case of an error
     */
    @Test
    @Override
    public void testReuseAfterClose() throws Exception {
        ILeaderElector elector = createLeaderElector("testReuseAfterClose");

        // First lifecycle
        assertTrue(elector.elect(Duration.ofMillis(getInitWaitMillis())), "Must become leader on first elect()");
        assertTrue(elector.isLeader());
        elector.close();
        assertFalse(elector.isLeader(), "Must not be leader after close()");

        // Allow JGroups to fully process the departure before reconnecting to the same cluster name.
        Thread.sleep(2000L);

        // Second lifecycle using the same elector instance
        assertTrue(elector.elect(Duration.ofMillis(getInitWaitMillis())), "Must become leader again after close()");
        assertTrue(elector.isLeader());
        elector.close();
        assertFalse(elector.isLeader());
    }
}
