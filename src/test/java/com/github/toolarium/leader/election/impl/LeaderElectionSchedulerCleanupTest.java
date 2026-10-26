/*
 * LeaderElectionSchedulerCleanupTest.java
 *
 * Copyright by toolarium, all rights reserved.
 */
package com.github.toolarium.leader.election.impl;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.github.toolarium.leader.election.dto.LeaderElectionConfiguration;
import com.github.toolarium.leader.election.dto.LeaderElectionInformation;
import com.github.toolarium.leader.election.exception.LeaderElectionException;
import java.lang.reflect.Method;
import java.time.Duration;
import org.junit.jupiter.api.Test;


/**
 * Tests the cleanup of the JVM shutdown hook: all registered electors are closed, even when one of them fails to close.
 *
 * @author patrick
 */
public class LeaderElectionSchedulerCleanupTest {

    /**
     * The shutdown cleanup closes every registered elector and continues after a failing close().
     *
     * @throws Exception in case of an error
     */
    @Test
    public void testShutdownCleanupClosesAllElectors() throws Exception {
        StubElector failing = new StubElector(true);
        StubElector leader = new StubElector(false);
        assertTrue(failing.elect(Duration.ofSeconds(1)));
        assertTrue(leader.elect(Duration.ofSeconds(1)));
        LeaderElectionScheduler.getInstance().register(failing);
        LeaderElectionScheduler.getInstance().register(leader);
        try {
            Method cleanup = LeaderElectionScheduler.class.getDeclaredMethod("cleanupLeaderElectors");
            cleanup.setAccessible(true);
            cleanup.invoke(LeaderElectionScheduler.getInstance());

            assertTrue(failing.closeCalled, "The failing elector must be closed");
            assertTrue(leader.closeCalled, "The cleanup must continue after a failing close()");
            assertFalse(leader.isLeader());
        } finally {
            LeaderElectionScheduler.getInstance().unregister(failing);
            LeaderElectionScheduler.getInstance().unregister(leader);
        }
    }


    /**
     * A minimal elector whose close() can fail.
     */
    private static final class StubElector extends AbstractLeaderElectorImpl<LeaderElectionConfiguration> {
        private final boolean failOnClose;
        private volatile boolean closeCalled;


        /**
         * Constructor for StubElector
         *
         * @param failOnClose true if close() fails
         */
        StubElector(boolean failOnClose) {
            super(new LeaderElectionInformation("scheduler-cleanup-" + System.nanoTime()), new LeaderElectionConfiguration());
            this.failOnClose = failOnClose;
        }


        /**
         * @see com.github.toolarium.leader.election.impl.AbstractLeaderElectorImpl#initializeImplementation()
         */
        @Override
        protected void initializeImplementation() throws LeaderElectionException {
            setLeader(true, null);
        }


        /**
         * @see com.github.toolarium.leader.election.impl.AbstractLeaderElectorImpl#close()
         */
        @Override
        public void close() throws LeaderElectionException {
            closeCalled = true;
            super.close();
            if (failOnClose) {
                throw new LeaderElectionException("Simulated close failure");
            }
        }
    }
}
