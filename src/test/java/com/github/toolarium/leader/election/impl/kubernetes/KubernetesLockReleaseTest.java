/*
 * KubernetesLockReleaseTest.java
 *
 * Copyright by toolarium, all rights reserved.
 */
package com.github.toolarium.leader.election.impl.kubernetes;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.github.toolarium.leader.election.dto.LeaderElectionConfiguration;
import com.github.toolarium.leader.election.dto.LeaderElectionInformation;
import io.kubernetes.client.extended.leaderelection.LeaderElectionRecord;
import io.kubernetes.client.extended.leaderelection.Lock;
import io.kubernetes.client.openapi.ApiClient;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;


/**
 * Verifies that closing the KUBERNETES leader releases the lock so that another node takes over on
 * its next retry instead of waiting for the full lease duration.
 *
 * @author patrick
 */
public class KubernetesLockReleaseTest {

    /**
     * In-memory lock record shared by all {@link SharedLock} views.
     */
    private static final class LockStore {
        private LeaderElectionRecord record;


        /**
         * Get the current record.
         *
         * @return the record or null
         */
        synchronized LeaderElectionRecord get() {
            return record;
        }


        /**
         * Create the record if none exists.
         *
         * @param newRecord the record
         * @return true if created
         */
        synchronized boolean create(LeaderElectionRecord newRecord) {
            if (record != null) {
                return false;
            }
            record = newRecord;
            return true;
        }


        /**
         * Replace the record.
         *
         * @param newRecord the record
         * @return always true
         */
        synchronized boolean update(LeaderElectionRecord newRecord) {
            record = newRecord;
            return true;
        }
    }


    /**
     * Lock view of one node on the shared store.
     */
    private static final class SharedLock implements Lock {
        private final LockStore store;
        private final String identity;


        /**
         * Constructor for SharedLock
         *
         * @param store the shared store
         * @param identity the identity of the node
         */
        SharedLock(LockStore store, String identity) {
            this.store = store;
            this.identity = identity;
        }


        /**
         * @see io.kubernetes.client.extended.leaderelection.Lock#get()
         */
        @Override
        public LeaderElectionRecord get() {
            return store.get();
        }


        /**
         * @see io.kubernetes.client.extended.leaderelection.Lock#create(io.kubernetes.client.extended.leaderelection.LeaderElectionRecord)
         */
        @Override
        public boolean create(LeaderElectionRecord record) {
            return store.create(record);
        }


        /**
         * @see io.kubernetes.client.extended.leaderelection.Lock#update(io.kubernetes.client.extended.leaderelection.LeaderElectionRecord)
         */
        @Override
        public boolean update(LeaderElectionRecord record) {
            return store.update(record);
        }


        /**
         * @see io.kubernetes.client.extended.leaderelection.Lock#identity()
         */
        @Override
        public String identity() {
            return identity;
        }


        /**
         * @see io.kubernetes.client.extended.leaderelection.Lock#describe()
         */
        @Override
        public String describe() {
            return "shared-lock/" + identity;
        }
    }


    /**
     * Create an elector working on the shared store.
     *
     * @param store the shared store
     * @param name the election name
     * @param config the configuration
     * @return the elector
     */
    private KubernetesLeaderElectorImpl createElector(LockStore store, String name, LeaderElectionConfiguration config) {
        return new KubernetesLeaderElectorImpl(new LeaderElectionInformation(name), config, new ApiClient()) {
            /**
             * @see com.github.toolarium.leader.election.impl.kubernetes.KubernetesLeaderElectorImpl#createLock(com.github.toolarium.leader.election.dto.LeaderElectionInformation)
             */
            @Override
            protected Lock createLock(LeaderElectionInformation leaderElectionInformation) {
                return new SharedLock(store, leaderElectionInformation.getIdentity());
            }
        };
    }


    /**
     * After the leader closes, the follower must become leader well before the lease duration (10 s).
     *
     * @throws Exception in case of an error
     */
    @Test
    public void testCloseReleasesLock() throws Exception {
        LockStore store = new LockStore();
        String name = "k8s-release-" + System.nanoTime();
        LeaderElectionConfiguration config = new LeaderElectionConfiguration(Duration.ofSeconds(10), Duration.ofSeconds(7), Duration.ofSeconds(1));
        KubernetesLeaderElectorImpl electorA = createElector(store, name, config);
        KubernetesLeaderElectorImpl electorB = createElector(store, name, config);
        try {
            assertTrue(electorA.elect(Duration.ofSeconds(10)), "A must become leader");
            assertFalse(electorB.elect(Duration.ofSeconds(10)), "B must be follower while A leads");

            electorA.close();
            assertNull(store.get().getHolderIdentity(), "close() must release the lock");

            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (!electorB.isLeader() && System.nanoTime() < deadline) {
                Thread.sleep(100);
            }
            assertTrue(electorB.isLeader(), "B must take over on its next retry, not after the full lease duration");
        } finally {
            electorA.close();
            electorB.close();
        }
    }
}
