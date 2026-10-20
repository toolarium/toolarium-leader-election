/*
 * AbstractLeaderElectorTest.java
 *
 * Copyright by toolarium, all rights reserved.
 */
package com.github.toolarium.leader.election;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.github.toolarium.leader.election.exception.LeaderElectionException;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
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
    protected abstract ILeaderElector createLeaderElector(String name) throws Exception;


    /**
     * Maximum milliseconds to wait for a node to establish leadership after elect().
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
     * Test simple leader initialization: create, elect (idempotent), verify leadership, close (idempotent).
     *
     * @throws Exception In case of an error
     */
    @Test
    public void testInitialize() throws Exception {
        LOG.debug("->Get leader");
        ILeaderElector leaderElector = createLeaderElector("testInitialize");
        LOG.debug("->Check leader before elect");
        assertFalse(leaderElector.isLeader());
        Thread.sleep(50L);
        LOG.debug("->Check leader");
        assertFalse(leaderElector.isLeader());
        LOG.debug("->Elect leader");
        assertTrue(leaderElector.elect(Duration.ofMillis(getInitWaitMillis())));
        LOG.debug("->Elect leader (idempotent)");
        assertTrue(leaderElector.elect(Duration.ofMillis(100)));
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
        ILeaderElector leaderElectorA = createLeaderElector("testMultipleLeaders");
        assertTrue(leaderElectorA.elect(Duration.ofMillis(getInitWaitMillis())));
        assertTrue(leaderElectorA.isLeader());

        ILeaderElector leaderElectorB = createLeaderElector("testMultipleLeaders");
        assertFalse(leaderElectorB.elect(Duration.ofMillis(getInitWaitMillis())));
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
     * Test runIfLeader: executes when leader, skips when not leader, and provides a valid lease.
     *
     * @throws Exception In case of an error
     */
    @Test
    public void testRunIfLeader() throws Exception {
        ILeaderElector leaderElector = createLeaderElector("testRunIfLeader");
        assertTrue(leaderElector.elect(Duration.ofMillis(getInitWaitMillis())));

        // Runnable overload: must execute when leader
        AtomicBoolean executed = new AtomicBoolean(false);
        assertTrue(leaderElector.runIfLeader("runnable", () -> executed.set(true)));
        assertTrue(executed.get());

        // LeaderTask overload: lease must be valid inside the task
        AtomicBoolean leaseValid = new AtomicBoolean(false);
        assertTrue(leaderElector.runIfLeader("task", lease -> leaseValid.set(lease.isValid())));
        assertTrue(leaseValid.get());

        leaderElector.close();

        // Must not execute after close (not leader)
        executed.set(false);
        assertFalse(leaderElector.runIfLeader("runnable", () -> executed.set(true)));
        assertFalse(executed.get());
    }


    /**
     * Test tryExtend: extends the lease from within a running task and resets getRemainingLeadTime().
     *
     * @throws Exception In case of an error
     */
    @Test
    public void testTryExtend() throws Exception {
        ILeaderElector leaderElector = createLeaderElector("testTryExtend");
        assertTrue(leaderElector.elect(Duration.ofMillis(getInitWaitMillis())));

        AtomicBoolean extended = new AtomicBoolean(false);
        AtomicBoolean leaseValidAfterExtend = new AtomicBoolean(false);
        leaderElector.runIfLeader("extend", lease -> {
            boolean result = lease.tryExtend();
            extended.set(result);
            leaseValidAfterExtend.set(lease.isValid());
        });
        assertTrue(extended.get(), "tryExtend() should return true while still leader");
        assertTrue(leaseValidAfterExtend.get(), "lease should still be valid after extension");

        leaderElector.close();
    }


    /**
     * Test getId(): returns a non-null, non-empty identifier that is stable across calls.
     *
     * @throws Exception In case of an error
     */
    @Test
    public void testGetId() throws Exception {
        ILeaderElector leaderElector = createLeaderElector("testGetId");
        String id = leaderElector.getId();
        assertNotNull(id, "getId() must never return null");
        assertFalse(id.isEmpty(), "getId() must never return an empty string");
        assertTrue(leaderElector.getId().equals(id), "getId() must return the same value on every call");
        leaderElector.close();
    }


    /**
     * Test getLeaderElectorTimestamp(): null before elect(), non-null after elect() as leader, null after close().
     *
     * @throws Exception In case of an error
     */
    @Test
    public void testGetLeaderElectorTimestamp() throws Exception {
        ILeaderElector leaderElector = createLeaderElector("testTimestamp");
        assertNull(leaderElector.getLeaderElectorTimestamp(), "timestamp must be null before elect()");
        assertTrue(leaderElector.elect(Duration.ofMillis(getInitWaitMillis())));
        assertNotNull(leaderElector.getLeaderElectorTimestamp(), "timestamp must be non-null after elect() as leader");
        leaderElector.close();
        assertNull(leaderElector.getLeaderElectorTimestamp(), "timestamp must be null after close()");
    }


    /**
     * Test all ILeaderLease accessors and verify() happy path from inside a running task.
     *
     * @throws Exception In case of an error
     */
    @Test
    public void testLeaseAccessors() throws Exception {
        ILeaderElector leaderElector = createLeaderElector("testLeaseAccessors");
        assertTrue(leaderElector.elect(Duration.ofMillis(getInitWaitMillis())));

        leaderElector.runIfLeader("lease-check", lease -> {
            // identity
            assertNotNull(lease.getId(), "lease.getId() must not be null");
            assertFalse(lease.getId().isEmpty(), "lease.getId() must not be empty");

            // structural metadata
            assertNotNull(lease.getLeaderElectionInformation(), "lease.getLeaderElectionInformation() must not be null");
            assertNotNull(lease.getLeaderElectionConfiguration(), "lease.getLeaderElectionConfiguration() must not be null");

            // timing
            assertNotNull(lease.getLeaderSince(), "lease.getLeaderSince() must not be null while leader");
            assertTrue(lease.getRemainingLeadTime().compareTo(Duration.ZERO) > 0,
                    "getRemainingLeadTime() must be positive immediately after election");
            assertFalse(lease.isExpiringSoon(Duration.ofMillis(1)),
                    "isExpiringSoon(1ms) must be false immediately after election");
            assertTrue(lease.isExpiringSoon(Duration.ofHours(1)),
                    "isExpiringSoon(1h) must be true since renewDeadline << 1 hour");

            // verify() must not throw when still leader
            lease.verify();
        });

        leaderElector.close();
    }


    /**
     * Test at-most-one guard: a second concurrent runIfLeader call with the same task name
     * returns false without executing the task.
     *
     * @throws Exception In case of an error
     */
    @Test
    public void testRunIfLeaderAtMostOne() throws Exception {
        ILeaderElector leaderElector = createLeaderElector("testAtMostOne");
        assertTrue(leaderElector.elect(Duration.ofMillis(getInitWaitMillis())));

        CountDownLatch taskStarted = new CountDownLatch(1);
        CountDownLatch releaseTask = new CountDownLatch(1);

        Thread bgThread = new Thread(() -> {
            try {
                leaderElector.runIfLeader("concurrent-task", lease -> {
                    taskStarted.countDown();
                    releaseTask.await(); // blocks until the main thread releases it
                });
            } catch (Exception e) {
                // ignored — task may be interrupted on close
            }
        });
        bgThread.start();

        assertTrue(taskStarted.await(2000L, TimeUnit.MILLISECONDS), "First task must start within 2s");

        // Second call with the same name must return false (at-most-one guard)
        assertFalse(leaderElector.runIfLeader("concurrent-task", () -> {}),
                "Concurrent runIfLeader with same name must return false");

        releaseTask.countDown();
        bgThread.join(2000L);
        leaderElector.close();
    }


    /**
     * Test that the task thread is interrupted when the elector is closed while the task is running,
     * and that the lease becomes invalid afterwards.
     *
     * @throws Exception In case of an error
     */
    @Test
    public void testRunIfLeaderInterruptedOnLeadershipLoss() throws Exception {
        ILeaderElector leaderElector = createLeaderElector("testInterruption");
        assertTrue(leaderElector.elect(Duration.ofMillis(getInitWaitMillis())));

        CountDownLatch taskStarted = new CountDownLatch(1);
        AtomicBoolean taskWasInterrupted = new AtomicBoolean(false);
        AtomicReference<ILeaderLease> capturedLease = new AtomicReference<>();

        Thread taskThread = new Thread(() -> {
            try {
                leaderElector.runIfLeader("blocking-task", lease -> {
                    capturedLease.set(lease);
                    taskStarted.countDown();
                    try {
                        Thread.sleep(30_000L); // blocks until interrupted
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
        leaderElector.close(); // triggers interruption via LeaderLeaseMonitor within ~100ms

        taskThread.join(3000L);
        assertFalse(taskThread.isAlive(), "Task thread must finish within 3s after close");
        assertTrue(taskWasInterrupted.get(), "Task thread must have been interrupted");

        // After close, the lease must be invalid and verify() must throw
        ILeaderLease lease = capturedLease.get();
        assertNotNull(lease);
        assertFalse(lease.isValid(), "Lease must be invalid after elector is closed");
        assertThrows(LeaderElectionException.class, lease::verify,
                "verify() must throw LeaderElectionException when lease is invalid");
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
