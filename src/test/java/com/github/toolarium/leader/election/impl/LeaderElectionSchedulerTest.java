/*
 * LeaderElectionSchedulerTest.java
 *
 * Copyright by toolarium, all rights reserved.
 */
package com.github.toolarium.leader.election.impl;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;

import com.github.toolarium.leader.election.ILeaderElector;
import com.github.toolarium.leader.election.ILeaderTask;
import com.github.toolarium.leader.election.exception.LeaderElectionException;
import java.lang.reflect.Field;
import java.time.Duration;
import java.time.Instant;
import java.util.Set;
import org.junit.jupiter.api.Test;


/**
 * Unit tests for {@link LeaderElectionScheduler} register/unregister behaviour.
 *
 * @author patrick
 */
public class LeaderElectionSchedulerTest {

    /**
     * register(null) must be a silent no-op.
     */
    @Test
    public void testRegisterNullIsNoOp() {
        assertDoesNotThrow(() -> LeaderElectionScheduler.getInstance().register(null));
    }


    /**
     * unregister(null) must be a silent no-op.
     */
    @Test
    public void testUnregisterNullIsNoOp() {
        assertDoesNotThrow(() -> LeaderElectionScheduler.getInstance().unregister(null));
    }


    /**
     * Registering an elector adds it to the internal set; registering the same instance a
     * second time does not duplicate it (Set semantics); unregistering removes it.
     *
     * @throws Exception In case of a reflection error
     */
    @Test
    public void testRegisterAndUnregister() throws Exception {
        LeaderElectionScheduler scheduler = LeaderElectionScheduler.getInstance();

        Field field = LeaderElectionScheduler.class.getDeclaredField("leaderElectors");
        field.setAccessible(true);
        @SuppressWarnings("unchecked")
        Set<ILeaderElector> set = (Set<ILeaderElector>) field.get(scheduler);

        ILeaderElector stub = new StubLeaderElector();
        int initialSize = set.size();

        scheduler.register(stub);
        assertEquals(initialSize + 1, set.size(), "Set must grow by 1 after register");

        // Registering the same instance again must not duplicate (Set semantics)
        scheduler.register(stub);
        assertEquals(initialSize + 1, set.size(), "Registering the same elector twice must not duplicate");

        scheduler.unregister(stub);
        assertEquals(initialSize, set.size(), "Set must shrink by 1 after unregister");
    }


    /**
     * Unregistering an elector that was never registered must be a silent no-op.
     *
     * @throws Exception In case of a reflection error
     */
    @Test
    public void testUnregisterNonRegisteredIsNoOp() throws Exception {
        LeaderElectionScheduler scheduler = LeaderElectionScheduler.getInstance();

        Field field = LeaderElectionScheduler.class.getDeclaredField("leaderElectors");
        field.setAccessible(true);
        @SuppressWarnings("unchecked")
        Set<ILeaderElector> set = (Set<ILeaderElector>) field.get(scheduler);

        int initialSize = set.size();
        scheduler.unregister(new StubLeaderElector()); // not registered
        assertEquals(initialSize, set.size(), "Unregistering an unknown elector must not change the set size");
    }


    /**
     * Minimal stub that satisfies the {@link ILeaderElector} interface for scheduler tests.
     */
    private static final class StubLeaderElector implements ILeaderElector {
        /**
         * @see com.github.toolarium.leader.election.ILeaderElector#getId()
         */
        @Override
        public String getId() {
            return "stub-" + System.identityHashCode(this);
        }

        /**
         * @see com.github.toolarium.leader.election.ILeaderElector#elect()
         */
        @Override
        public boolean elect() throws LeaderElectionException, InterruptedException {
            return false;
        }

        /**
         * @see com.github.toolarium.leader.election.ILeaderElector#elect(java.time.Duration)
         */
        @Override
        public boolean elect(Duration timeout) throws LeaderElectionException, InterruptedException {
            return false;
        }

        /**
         * @see com.github.toolarium.leader.election.ILeaderElector#close()
         */
        @Override
        public void close() throws LeaderElectionException {
            // NOP
        }

        /**
         * @see com.github.toolarium.leader.election.ILeaderElector#isLeader()
         */
        @Override
        public boolean isLeader() {
            return false;
        }

        /**
         * @see com.github.toolarium.leader.election.ILeaderElector#getLeaderElectorTimestamp()
         */
        @Override
        public Instant getLeaderElectorTimestamp() {
            return null;
        }

        /**
         * @see com.github.toolarium.leader.election.ILeaderElector#runIfLeader(String, ILeaderTask)
         */
        @Override
        public boolean runIfLeader(String name, ILeaderTask task) throws LeaderElectionException {
            return false;
        }
    }
}
