/*
 * SessionTransitionTest.java
 *
 * Copyright by toolarium, all rights reserved.
 */
package com.github.toolarium.leader.election.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.github.toolarium.leader.election.dto.LeaderElectionConfiguration;
import com.github.toolarium.leader.election.dto.LeaderElectionInformation;
import com.github.toolarium.leader.election.exception.LeaderElectionException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.Test;


/**
 * Tests the transitions between sessions: a close() during a slow initialization, and an elect() while the lock of the
 * closed session is still to be released.
 *
 * @author patrick
 */
public class SessionTransitionTest {
    private static final String INIT = "init";
    private static final String RELEASE = "release";


    /**
     * A close() that cannot wait for a slow initialization returns, and the elector is closed as soon as the initialization
     * has finished: no resource of the initialization is left with a closed elector.
     *
     * @throws Exception in case of an error
     */
    @Test
    public void testCloseDuringSlowInitialization() throws Exception {
        SessionElector elector = new SessionElector();
        elector.initRelease = new CountDownLatch(1);
        Thread electThread = new Thread(() -> {
            try {
                elector.elect(Duration.ofSeconds(10));
            } catch (Exception e) {
                // NOP
            }
        }, "test-slow-init");
        electThread.start();
        assertTrue(elector.initStarted.await(5, TimeUnit.SECONDS));

        long start = System.nanoTime();
        elector.close();
        long closeMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
        assertTrue(closeMillis < 3000, "close() must not wait for the initialization, took " + closeMillis + " ms");

        elector.initRelease.countDown();
        electThread.join(5000);
        assertTrue(await(() -> elector.openResources.get() == 0, 5000), "The resources of the initialization must be released");
        assertFalse(elector.isInitialized(), "The elector must be closed after the initialization");
        assertFalse(elector.isLeader());
    }


    /**
     * elect() with a timeout returns within its timeout while another thread runs a slow initialization.
     *
     * @throws Exception in case of an error
     */
    @Test
    public void testElectTimeoutCoversSlowInitialization() throws Exception {
        SessionElector elector = new SessionElector();
        elector.initRelease = new CountDownLatch(1);
        Thread electThread = new Thread(() -> {
            try {
                elector.elect(Duration.ofSeconds(10));
            } catch (Exception e) {
                // NOP
            }
        }, "test-slow-init-owner");
        electThread.start();
        try {
            assertTrue(elector.initStarted.await(5, TimeUnit.SECONDS));

            long start = System.nanoTime();
            assertFalse(elector.elect(Duration.ofMillis(500)), "elect() must not lead while the initialization runs");
            long electMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
            assertTrue(electMillis < 2000, "elect() must return within its timeout, took " + electMillis + " ms");
        } finally {
            elector.initRelease.countDown();
            electThread.join(5000);
            elector.close();
        }
    }


    /**
     * elect() after a close() whose release waits for a running task starts the new session only after that release, so the
     * release of the old session cannot give up the lock of the new one.
     *
     * @throws Exception in case of an error
     */
    @Test
    public void testElectWaitsForReleaseOfClosedSession() throws Exception {
        SessionElector elector = new SessionElector();
        assertTrue(elector.elect(Duration.ofSeconds(5)));
        CountDownLatch taskStarted = new CountDownLatch(1);
        CountDownLatch finishTask = new CountDownLatch(1);
        Thread taskThread = new Thread(() -> {
            try {
                elector.runIfLeader("session-transition-task", lease -> {
                    taskStarted.countDown();
                    // ignores interrupts like a blocking I/O call
                    boolean done = false;
                    while (!done) {
                        try {
                            done = finishTask.await(10, TimeUnit.SECONDS);
                        } catch (InterruptedException e) {
                            // keep running
                        }
                    }
                });
            } catch (Exception e) {
                // NOP
            }
        }, "test-session-task");
        taskThread.start();
        assertTrue(taskStarted.await(5, TimeUnit.SECONDS));

        // the task still runs after the close timeout: the release is delayed
        elector.close();
        Thread electThread = new Thread(() -> {
            try {
                elector.elect(Duration.ofSeconds(10));
            } catch (Exception e) {
                // NOP
            }
        }, "test-reelect");
        electThread.start();
        Thread.sleep(500);
        assertTrue(electThread.isAlive(), "elect() must wait for the release of the closed session");

        finishTask.countDown();
        electThread.join(5000);
        taskThread.join(5000);
        assertEquals(Arrays.asList(INIT, RELEASE, INIT), elector.events, "The new session must start after the release");
        assertTrue(elector.isLeader());
        elector.close();
    }


    /**
     * Wait for a condition.
     *
     * @param condition the condition
     * @param timeoutMillis the timeout
     * @return true if the condition is met
     * @throws InterruptedException if interrupted
     */
    private static boolean await(BooleanSupplier condition, long timeoutMillis) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis);
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() > deadline) {
                return false;
            }
            Thread.sleep(20);
        }
        return true;
    }


    /**
     * An elector that opens a resource on initialization and releases it on close, like the strategies do with their lock.
     */
    private static final class SessionElector extends AbstractLeaderElectorImpl<LeaderElectionConfiguration> {
        private final CountDownLatch initStarted = new CountDownLatch(1);
        private final AtomicInteger openResources = new AtomicInteger();
        private final List<String> events = Collections.synchronizedList(new ArrayList<>());
        private volatile CountDownLatch initRelease;


        /**
         * Constructor for SessionElector
         */
        SessionElector() {
            super(new LeaderElectionInformation("session-transition-" + System.nanoTime()), new LeaderElectionConfiguration().setCloseTimeout(Duration.ofMillis(300)));
        }


        /**
         * @see com.github.toolarium.leader.election.impl.AbstractLeaderElectorImpl#initializeImplementation()
         */
        @Override
        protected void initializeImplementation() throws LeaderElectionException {
            initStarted.countDown();
            CountDownLatch release = initRelease;
            if (release != null) {
                try {
                    release.await(10, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            openResources.incrementAndGet();
            events.add(INIT);
            setLeader(true, null);
        }


        /**
         * @see com.github.toolarium.leader.election.impl.AbstractLeaderElectorImpl#close()
         */
        @Override
        public void close() throws LeaderElectionException {
            closeSerialized(getLeaderElectionConfiguration().getCloseTimeout().toMillis(), this::closeNow);
        }


        /**
         * Close the session and release its resource once the tasks ended.
         *
         * @throws LeaderElectionException in case of an error
         */
        private void closeNow() throws LeaderElectionException {
            boolean tasksFinished = closeAndAwaitTasks();
            releaseWhenTasksFinished(tasksFinished, "test-session-release", () -> {
                events.add(RELEASE);
                openResources.decrementAndGet();
            });
        }
    }
}
