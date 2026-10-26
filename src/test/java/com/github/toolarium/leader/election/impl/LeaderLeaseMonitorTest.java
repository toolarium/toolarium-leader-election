/*
 * LeaderLeaseMonitorTest.java
 *
 * Copyright by toolarium, all rights reserved.
 */
package com.github.toolarium.leader.election.impl;

import static org.junit.jupiter.api.Assertions.assertTrue;

import com.github.toolarium.leader.election.dto.LeaderElectionConfiguration;
import com.github.toolarium.leader.election.dto.LeaderElectionInformation;
import com.github.toolarium.leader.election.exception.LeaderElectionException;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;


/**
 * Tests that the lease monitor survives failing lease checks and still interrupts a task once its lead is lost.
 *
 * @author patrick
 */
public class LeaderLeaseMonitorTest {

    /**
     * Repeated exceptions and an error while checking the leases do not stop the monitor.
     *
     * @throws Exception in case of an error
     */
    @Test
    public void testMonitorSurvivesFailingChecks() throws Exception {
        FailingElector elector = new FailingElector();
        assertTrue(elector.elect(Duration.ofSeconds(1)));
        AtomicBoolean interrupted = new AtomicBoolean(false);
        try {
            assertTrue(elector.runIfLeader("lease-monitor-failing-checks", lease -> {
                // the monitor checks every 100 ms: several exceptions in a row, then an error
                elector.failure = new IllegalStateException("Simulated check failure");
                Thread.sleep(500);
                elector.failure = new AssertionError("Simulated fatal check failure");
                Thread.sleep(300);
                elector.failure = null;

                // the monitor still works: losing the lead interrupts the task
                elector.callback(false);
                try {
                    Thread.sleep(5000);
                } catch (InterruptedException e) {
                    interrupted.set(true);
                }
            }));
            assertTrue(interrupted.get(), "The monitor must interrupt the task after the failing checks");
        } finally {
            elector.failure = null;
            elector.close();
        }
    }


    /**
     * An elector whose isLeader() can fail.
     */
    private static final class FailingElector extends AbstractLeaderElectorImpl<LeaderElectionConfiguration> {
        private volatile Throwable failure;


        /**
         * Constructor for FailingElector
         */
        FailingElector() {
            super(new LeaderElectionInformation("lease-monitor-" + System.nanoTime()), new LeaderElectionConfiguration());
        }


        /**
         * @see com.github.toolarium.leader.election.impl.AbstractLeaderElectorImpl#initializeImplementation()
         */
        @Override
        protected void initializeImplementation() throws LeaderElectionException {
            setLeader(true, null);
        }


        /**
         * @see com.github.toolarium.leader.election.impl.AbstractLeaderElectorImpl#isLeader()
         */
        @Override
        public boolean isLeader() {
            Throwable t = failure;
            if (t instanceof RuntimeException) {
                throw (RuntimeException) t;
            }
            if (t instanceof Error) {
                throw (Error) t;
            }
            return super.isLeader();
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
}
