/*
 * KubernetesFollowerElectTest.java
 *
 * Copyright by toolarium, all rights reserved.
 */
package com.github.toolarium.leader.election.impl.kubernetes;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;

import com.github.toolarium.leader.election.dto.LeaderElectionConfiguration;
import com.github.toolarium.leader.election.dto.LeaderElectionInformation;
import io.kubernetes.client.extended.leaderelection.LeaderElectionRecord;
import io.kubernetes.client.extended.leaderelection.Lock;
import io.kubernetes.client.openapi.ApiClient;
import java.time.Duration;
import java.util.Date;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;


/**
 * Verifies that elect() without a timeout returns on a KUBERNETES follower.
 *
 * @author patrick
 */
public class KubernetesFollowerElectTest {

    /**
     * Lock that is permanently held (and renewed) by another identity.
     */
    private static final class ForeignHeldLock implements Lock {
        private final String identity;


        /**
         * Constructor for ForeignHeldLock
         *
         * @param identity the identity of this node
         */
        ForeignHeldLock(String identity) {
            this.identity = identity;
        }


        /**
         * @see io.kubernetes.client.extended.leaderelection.Lock#get()
         */
        @Override
        public LeaderElectionRecord get() {
            Date now = new Date();
            return new LeaderElectionRecord("other-node", 10, now, now, 0);
        }


        /**
         * @see io.kubernetes.client.extended.leaderelection.Lock#create(io.kubernetes.client.extended.leaderelection.LeaderElectionRecord)
         */
        @Override
        public boolean create(LeaderElectionRecord record) {
            return false;
        }


        /**
         * @see io.kubernetes.client.extended.leaderelection.Lock#update(io.kubernetes.client.extended.leaderelection.LeaderElectionRecord)
         */
        @Override
        public boolean update(LeaderElectionRecord record) {
            return false;
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
            return "foreign-held-lock";
        }
    }


    /**
     * A follower must get a definitive "not leader" result, so elect() without a timeout returns.
     *
     * @throws Exception in case of an error
     */
    @Test
    public void testFollowerElectReturns() throws Exception {
        LeaderElectionInformation info = new LeaderElectionInformation("k8s-follower-" + System.nanoTime());
        LeaderElectionConfiguration config = new LeaderElectionConfiguration(Duration.ofSeconds(10), Duration.ofSeconds(7), Duration.ofSeconds(1));
        KubernetesLeaderElectorImpl elector = new KubernetesLeaderElectorImpl(info, config, new ApiClient()) {
            /**
             * @see com.github.toolarium.leader.election.impl.kubernetes.KubernetesLeaderElectorImpl#createLock(com.github.toolarium.leader.election.dto.LeaderElectionInformation)
             */
            @Override
            protected Lock createLock(LeaderElectionInformation leaderElectionInformation) {
                return new ForeignHeldLock(leaderElectionInformation.getIdentity());
            }
        };

        AtomicReference<Throwable> error = new AtomicReference<>();
        AtomicReference<Boolean> result = new AtomicReference<>();
        Thread electThread = new Thread(() -> {
            try {
                result.set(elector.elect());
            } catch (Throwable e) {
                error.set(e);
            }
        }, "test-k8s-follower-elect");
        electThread.setDaemon(true);
        electThread.start();
        electThread.join(TimeUnit.SECONDS.toMillis(10));
        try {
            assertFalse(electThread.isAlive(), "elect() without timeout blocks forever on a follower");
            assertNull(error.get());
            assertFalse(result.get());
            assertFalse(elector.isLeader());
        } finally {
            elector.close();
        }
    }
}
