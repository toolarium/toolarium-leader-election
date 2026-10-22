/*
 * DatabaseLeaderLeaseTest.java
 *
 * Copyright by toolarium, all rights reserved.
 */
package com.github.toolarium.leader.election.impl.database;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.github.toolarium.leader.election.ILeaderElector;
import com.github.toolarium.leader.election.ILeaderLease;
import com.github.toolarium.leader.election.LeaderElectionFactory;
import com.github.toolarium.leader.election.dto.LeaderElectionInformation;
import com.github.toolarium.leader.election.dto.LeaderElectionStrategy;
import com.github.toolarium.leader.election.dto.db.DatabaseLeaderElectionConfiguration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;


/**
 * Unit tests covering the lease and {@code runIfLeader} fixes from the DATABASE code review.
 * Although these fixes live in {@link com.github.toolarium.leader.election.impl.AbstractLeaderElectorImpl}
 * (and therefore apply to every strategy), they are exercised here using the DATABASE strategy
 * to stay consistent with the scope of the original code review.
 *
 * <p>Issues covered:
 * <ul>
 *   <li>#4 – leadership-term counter: a lease captured in term T is invalid even after the
 *       elector re-acquires leadership in term T+1.</li>
 *   <li>#7 – interrupt flag is not leaked to the calling thread when the lease monitor
 *       interrupts a running task because leadership was lost.</li>
 *   <li>#9 – {@link RuntimeException} thrown inside a {@code runIfLeader} task is rethrown
 *       unchanged (not wrapped in {@link com.github.toolarium.leader.election.exception.LeaderElectionException}).</li>
 * </ul>
 *
 * @author patrick
 */
public class DatabaseLeaderLeaseTest {

    // -----------------------------------------------------------------------
    // Issue #4: leadership-term counter — lease is invalid after re-acquisition.
    // -----------------------------------------------------------------------

    /**
     * Verify that a {@link ILeaderLease} captured during term T reports {@code isValid() == false}
     * once the elector closes (leadership lost) and then re-acquires leadership in term T+1.
     *
     * <p>Before the fix, the lease only checked {@code elector.isLeader()} and would incorrectly
     * become valid again if the same elector object re-gained leadership after a close/re-elect
     * cycle, allowing a stale task to re-execute with a lease that belongs to a previous term.
     *
     * @throws Exception in case of an error
     */
    @Test
    public void testLeaseInvalidAfterTermAdvances() throws Exception {
        ILeaderElector elector = createElector("term-test");
        assertTrue(elector.elect(), "Elector must establish leadership");

        // Capture a reference to the lease while the elector is leader (term = T).
        // The task returns immediately; the lease object survives beyond the task's lifetime.
        AtomicReference<ILeaderLease> savedLease = new AtomicReference<>();
        elector.runIfLeader("capture-lease", lease -> savedLease.set(lease));
        assertNotNull(savedLease.get());
        assertTrue(savedLease.get().isValid(),
                "Lease must be valid immediately after capture while elector is still leader");

        // Close the elector — leadership is lost, term does not increment on step-down.
        elector.close();
        assertFalse(savedLease.get().isValid(),
                "Lease must be invalid after elector is closed");

        // Re-elect the same elector object — this is a real new acquisition so the term increments.
        assertTrue(elector.elect(), "Re-elect after close must succeed");
        assertTrue(elector.isLeader(), "Elector must be leader again after re-elect");

        // The old lease was created in the previous term; even though the elector is leader
        // again, the term mismatch means the lease must remain invalid.
        assertFalse(savedLease.get().isValid(),
                "Lease from previous term must be invalid even after elector re-acquires leadership in a new term");

        elector.close();
    }


    // -----------------------------------------------------------------------
    // Issue #7: interrupt flag must not be leaked to the calling thread.
    // -----------------------------------------------------------------------

    /**
     * Verify that when the {@code LeaderLeaseMonitor} interrupts a running task because
     * leadership was lost, the interrupt flag is <em>not</em> left set on the task thread
     * after {@code runIfLeader()} returns.
     *
     * <p>Without the fix, the interrupt flag set by the monitor would be visible to the caller
     * after {@code runIfLeader()} returned, causing unexpected {@code InterruptedException}s
     * in subsequent blocking calls on the same thread.
     *
     * @throws Exception in case of an error
     */
    @Test
    public void testInterruptFlagNotLeakedAfterMonitorInterrupt() throws Exception {
        ILeaderElector elector = createElector("interrupt-leak");
        assertTrue(elector.elect());

        CountDownLatch taskStarted = new CountDownLatch(1);
        CountDownLatch taskFinished = new CountDownLatch(1);
        AtomicBoolean interruptFlagAfterReturn = new AtomicBoolean(true); // pessimistic default

        // Run the blocking task on a separate thread so the main thread can call close().
        Thread taskThread = new Thread(() -> {
            try {
                elector.runIfLeader("blocking", lease -> {
                    taskStarted.countDown();
                    Thread.sleep(30_000L); // blocks until the monitor interrupts it
                });
            } catch (Exception ignored) {
                // runIfLeader may surface a LeaderElectionException on close — ignore it here.
            }
            // Record the interrupt flag on THIS thread immediately after runIfLeader() returns.
            interruptFlagAfterReturn.set(Thread.currentThread().isInterrupted());
            taskFinished.countDown();
        }, "interrupt-test-task");
        taskThread.start();

        assertTrue(taskStarted.await(2000L, TimeUnit.MILLISECONDS),
                "Task must start within 2 s");

        // close() sets isLeader=null; the LeaderLeaseMonitor interrupts the task within ~100 ms.
        elector.close();

        assertTrue(taskFinished.await(3000L, TimeUnit.MILLISECONDS),
                "Task must finish within 3 s after close");
        assertFalse(taskThread.isAlive(), "Task thread must have terminated");
        assertFalse(interruptFlagAfterReturn.get(),
                "Interrupt flag must NOT be set on the task thread after runIfLeader() returns");
    }


    // -----------------------------------------------------------------------
    // Issue #7 (exactly-once): monitor must interrupt the task at most once.
    // -----------------------------------------------------------------------

    /**
     * Verify that the {@link com.github.toolarium.leader.election.impl.LeaderLeaseMonitor}
     * interrupts the task thread <em>exactly once</em>, even though the monitor polls every
     * ~100 ms.
     * The {@code AtomicBoolean interruptedByMonitor} in
     * {@link com.github.toolarium.leader.election.impl.DefaultLeaderLease} ensures
     * {@code compareAndSet(false, true)} succeeds only for the first monitor tick; subsequent
     * ticks skip the {@code interrupt()} call entirely.
     * Without the fix the monitor would repeatedly call {@code interrupt()} after leadership
     * was lost, waking the task from every subsequent {@code sleep()} with an extra
     * {@code InterruptedException}.
     *
     * @throws Exception in case of an error
     */
    @Test
    public void testMonitorInterruptsTaskAtMostOnce() throws Exception {
        ILeaderElector elector = createElector("interrupt-once");
        assertTrue(elector.elect());

        CountDownLatch taskStarted = new CountDownLatch(1);
        AtomicInteger interruptCount = new AtomicInteger(0);
        CountDownLatch taskFinished = new CountDownLatch(1);

        Thread taskThread = new Thread(() -> {
            try {
                elector.runIfLeader("count-ints", lease -> {
                    taskStarted.countDown();
                    // Spin-sleep; each InterruptedException here was caused by a monitor
                    // interrupt (the catch swallows it to count it rather than re-throw).
                    while (lease.isValid()) {
                        try {
                            Thread.sleep(30L);
                        } catch (InterruptedException e) {
                            interruptCount.incrementAndGet();
                        }
                    }
                    // After the lease becomes invalid, sleep across several monitor cycles
                    // to detect any follow-up interrupts that must NOT occur.
                    for (int i = 0; i < 5; i++) {
                        try {
                            Thread.sleep(120L);
                        } catch (InterruptedException e) {
                            interruptCount.incrementAndGet();
                        }
                    }
                });
            } catch (Exception ignored) {
                // runIfLeader may surface exceptions on close — intentionally ignored
            }
            taskFinished.countDown();
        }, "interrupt-once-task");
        taskThread.start();

        assertTrue(taskStarted.await(2000L, TimeUnit.MILLISECONDS), "Task must start within 2 s");

        // close() sets isLeader=null; the monitor will interrupt the task within ~100 ms.
        elector.close();

        assertTrue(taskFinished.await(5000L, TimeUnit.MILLISECONDS), "Task must finish within 5 s");
        assertFalse(taskThread.isAlive(), "Task thread must have terminated");
        assertEquals(1, interruptCount.get(),
                "Monitor must interrupt the task thread exactly once, got: " + interruptCount.get());
    }


    // -----------------------------------------------------------------------
    // Issue #9: RuntimeException thrown inside a task is rethrown unchanged.
    // -----------------------------------------------------------------------

    /**
     * Verify that a {@link RuntimeException} thrown by the task passed to
     * {@code runIfLeader()} propagates out as-is — it must not be wrapped in a
     * {@link com.github.toolarium.leader.election.exception.LeaderElectionException}.
     *
     * <p>Before the fix there was no dedicated {@code catch (RuntimeException e)} branch;
     * the general {@code catch (Exception e)} clause would wrap the exception, losing the
     * original type and making it impossible for callers to handle specific runtime errors.
     *
     * @throws Exception in case of an error
     */
    @Test
    public void testRuntimeExceptionRethrownFromRunIfLeader() throws Exception {
        ILeaderElector elector = createElector("rte-test");
        assertTrue(elector.elect());

        IllegalStateException original = new IllegalStateException("deliberate failure from task");
        IllegalStateException caught = null;
        try {
            elector.runIfLeader("rte-task", lease -> {
                throw original;
            });
        } catch (IllegalStateException e) {
            caught = e;
        }

        assertNotNull(caught, "IllegalStateException must propagate out of runIfLeader()");
        assertSame(original, caught,
                "The propagated exception must be the exact same instance (not a wrapper)");

        elector.close();
    }


    // -----------------------------------------------------------------------
    // Helper
    // -----------------------------------------------------------------------

    /**
     * Create a DATABASE leader elector backed by a fresh H2 in-memory database.
     *
     * @param groupName the election group name
     * @return the leader elector
     * @throws Exception in case of an error
     */
    private ILeaderElector createElector(String groupName) throws Exception {
        JdbcDataSource ds = new JdbcDataSource();
        ds.setURL("jdbc:h2:mem:lease_" + groupName.replace("-", "_") + ";DB_CLOSE_DELAY=-1");
        ds.setUser("sa");
        ds.setPassword("");
        DatabaseLeaderElectionConfiguration config = new DatabaseLeaderElectionConfiguration(2);
        config.setDataSource(ds);
        return LeaderElectionFactory.getInstance().getLeaderElection(
                LeaderElectionStrategy.DATABASE, new LeaderElectionInformation(groupName), config);
    }
}
