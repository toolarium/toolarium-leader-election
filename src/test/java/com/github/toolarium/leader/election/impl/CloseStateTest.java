/*
 * CloseStateTest.java
 *
 * Copyright by toolarium, all rights reserved.
 */
package com.github.toolarium.leader.election.impl;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.github.toolarium.leader.election.dto.LeaderElectionConfiguration;
import com.github.toolarium.leader.election.dto.LeaderElectionInformation;
import com.github.toolarium.leader.election.exception.LeaderElectionException;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;


/**
 * Verifies that late callbacks from background threads cannot change the state of a closed elector.
 *
 * @author patrick
 */
public class CloseStateTest {

    /**
     * Minimal elector whose background "callbacks" are driven by the test.
     */
    private static final class StubElector extends AbstractLeaderElectorImpl<LeaderElectionConfiguration> {

        /**
         * Constructor for StubElector
         */
        StubElector() {
            super(new LeaderElectionInformation("close-state-" + System.nanoTime()), new LeaderElectionConfiguration());
        }


        /**
         * @see com.github.toolarium.leader.election.impl.AbstractLeaderElectorImpl#initializeImplementation()
         */
        @Override
        protected void initializeImplementation() throws LeaderElectionException {
            setLeader(true, null);
        }


        /**
         * Simulate a callback of a background thread.
         *
         * @param leader the reported leader state
         */
        void callback(boolean leader) {
            setLeader(leader, null);
        }
    }


    /**
     * Callbacks arriving after close() must be ignored, whatever their value.
     *
     * @throws Exception in case of an error
     */
    @Test
    public void testLateCallbacksAfterCloseAreIgnored() throws Exception {
        StubElector elector = new StubElector();
        assertTrue(elector.elect(Duration.ofSeconds(1)));
        elector.close();

        elector.callback(true);
        assertFalse(elector.isLeader(), "a closed elector must not report leadership");
        elector.callback(false);
        assertFalse(elector.hasElectionResult(), "a late 'false' must not turn the cleared state into a result");

        // Re-election after close still works.
        assertTrue(elector.elect(Duration.ofSeconds(1)));
        elector.close();
    }


    /**
     * A callback thread hammering setLeader(true) while close() runs must never leave a closed
     * elector reporting leadership.
     *
     * @throws Exception in case of an error
     */
    @Test
    public void testConcurrentCallbackDuringClose() throws Exception {
        for (int i = 0; i < 200; i++) {
            StubElector elector = new StubElector();
            assertTrue(elector.elect(Duration.ofSeconds(1)));

            AtomicBoolean stop = new AtomicBoolean(false);
            Thread callbackThread = new Thread(() -> {
                while (!stop.get()) {
                    elector.callback(true);
                }
            });
            callbackThread.start();
            elector.close();
            stop.set(true);
            callbackThread.join();

            assertFalse(elector.isLeader(), "iteration " + i + ": closed elector reports leadership");
        }
    }


    /**
     * close() called from inside a leader task must not wait for the whole close timeout (the task's
     * read lock can never be upgraded), and elect() called afterwards from the same task must fail
     * fast instead of deadlocking.
     *
     * @throws Exception in case of an error
     */
    @Test
    public void testCloseAndElectFromWithinTask() throws Exception {
        StubElector elector = new StubElector();
        elector.getLeaderElectionConfiguration().setCloseTimeout(Duration.ofSeconds(5));
        assertTrue(elector.elect(Duration.ofSeconds(1)));

        AtomicLong closeMillis = new AtomicLong(-1);
        AtomicReference<Throwable> electError = new AtomicReference<>();
        Thread taskThread = new Thread(() -> {
            try {
                elector.runIfLeader("self-close", lease -> {
                    long start = System.nanoTime();
                    elector.close();
                    closeMillis.set(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start));
                    try {
                        elector.elect(Duration.ofSeconds(1));
                    } catch (LeaderElectionException e) {
                        electError.set(e);
                    }
                });
            } catch (Exception e) {
                electError.set(e);
            }
        }, "test-self-close");
        taskThread.start();
        taskThread.join(TimeUnit.SECONDS.toMillis(10));

        assertFalse(taskThread.isAlive(), "elect() from within a task deadlocked");
        assertTrue(closeMillis.get() >= 0 && closeMillis.get() < 2000, "close() from within a task waited " + closeMillis.get() + "ms");
        assertTrue(electError.get() instanceof LeaderElectionException, "elect() from within a task of a closed elector must fail fast");
        assertFalse(elector.isLeader());
    }
}
