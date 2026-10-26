/*
 * KubernetesRecoveryTest.java
 *
 * Copyright by toolarium, all rights reserved.
 */
package com.github.toolarium.leader.election.impl.kubernetes;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.github.toolarium.leader.election.dto.LeaderElectionConfiguration;
import com.github.toolarium.leader.election.dto.LeaderElectionInformation;
import io.kubernetes.client.extended.leaderelection.LeaderElectionRecord;
import io.kubernetes.client.extended.leaderelection.Lock;
import io.kubernetes.client.openapi.ApiClient;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.Test;


/**
 * Tests how the KUBERNETES strategy recovers from lock failures and how it releases the lock on close.
 *
 * @author patrick
 */
public class KubernetesRecoveryTest {

    /**
     * A leader whose renewals fail loses the lead and takes it back once the lock works again.
     *
     * @throws Exception in case of an error
     */
    @Test
    public void testLeaderRecoversAfterRenewalsFail() throws Exception {
        ControllableLock lock = new ControllableLock("recovery-node");
        KubernetesLeaderElectorImpl elector = createElector(lock, "k8s-recovery-renew", new LeaderElectionConfiguration(Duration.ofSeconds(4), Duration.ofSeconds(2), Duration.ofMillis(500)));
        try {
            assertTrue(elector.elect(Duration.ofSeconds(10)));

            lock.failUpdates = true;
            assertTrue(await(() -> !elector.isLeader(), 6000), "The leader must lose the lead when its renewals fail");

            lock.failUpdates = false;
            assertTrue(await(elector::isLeader, 8000), "The node must take the lead back once the lock works again");
        } finally {
            elector.close();
        }
    }


    /**
     * When renewals hang in the API server, the lead ends at the renew deadline, although the client still reports it.
     *
     * @throws Exception in case of an error
     */
    @Test
    public void testLeadEndsAtRenewDeadlineWhenRenewalsHang() throws Exception {
        ControllableLock lock = new ControllableLock("hanging-renewal-node");
        KubernetesLeaderElectorImpl elector = createElector(lock, "k8s-recovery-hanging-renewal", new LeaderElectionConfiguration(Duration.ofSeconds(4), Duration.ofSeconds(2), Duration.ofMillis(500)));
        CountDownLatch hang = new CountDownLatch(1);
        try {
            assertTrue(elector.elect(Duration.ofSeconds(10)));

            lock.hangUpdates = hang;
            assertTrue(await(() -> !elector.isLeader(), 3500), "The lead must end at the renew deadline when renewals hang");
        } finally {
            hang.countDown();
            elector.close();
        }
    }


    /**
     * Errors reading the lock are retried until the lock can be read.
     *
     * @throws Exception in case of an error
     */
    @Test
    public void testLockReadErrorsAreRetried() throws Exception {
        ControllableLock lock = new ControllableLock("read-error-node");
        lock.failingReads.set(3);
        KubernetesLeaderElectorImpl elector = createElector(lock, "k8s-recovery-read", new LeaderElectionConfiguration(Duration.ofSeconds(4), Duration.ofSeconds(2), Duration.ofMillis(500)));
        try {
            assertTrue(elector.elect(Duration.ofSeconds(15)), "The node must lead once the lock can be read");
            assertEquals(0, lock.failingReads.get());
        } finally {
            elector.close();
        }
    }


    /**
     * close() releases the lock of the leader: the record keeps no holder.
     *
     * @throws Exception in case of an error
     */
    @Test
    public void testCloseReleasesTheLock() throws Exception {
        ControllableLock lock = new ControllableLock("release-node");
        KubernetesLeaderElectorImpl elector = createElector(lock, "k8s-recovery-release", new LeaderElectionConfiguration(Duration.ofSeconds(10), Duration.ofSeconds(7), Duration.ofSeconds(1)));
        assertTrue(elector.elect(Duration.ofSeconds(10)));
        elector.close();
        assertEquals(1, lock.releases.get(), "close() must write the release");
        assertEquals(null, lock.record.getHolderIdentity());
    }


    /**
     * A release that fails does not make close() fail; the lease expires instead.
     *
     * @throws Exception in case of an error
     */
    @Test
    public void testFailingReleaseIsIgnored() throws Exception {
        ControllableLock lock = new ControllableLock("failing-release-node");
        KubernetesLeaderElectorImpl elector = createElector(lock, "k8s-recovery-failing-release", new LeaderElectionConfiguration(Duration.ofSeconds(10), Duration.ofSeconds(7), Duration.ofSeconds(1)));
        assertTrue(elector.elect(Duration.ofSeconds(10)));
        lock.failRelease = true;
        elector.close();
        assertEquals("failing-release-node", lock.record.getHolderIdentity(), "The record must stay as it was");
    }


    /**
     * When the release of the kubernetes client fails, close() releases the lock itself.
     *
     * @throws Exception in case of an error
     */
    @Test
    public void testReleaseIsRetriedWhenTheClientFails() throws Exception {
        ControllableLock lock = new ControllableLock("retried-release-node");
        KubernetesLeaderElectorImpl elector = createElector(lock, "k8s-recovery-retried-release", new LeaderElectionConfiguration(Duration.ofSeconds(10), Duration.ofSeconds(7), Duration.ofSeconds(1)));
        assertTrue(elector.elect(Duration.ofSeconds(10)));
        lock.rejectedReleases.set(1);
        elector.close();
        assertEquals(0, lock.rejectedReleases.get(), "The release of the kubernetes client must have been rejected");
        assertEquals(1, lock.releases.get(), "close() must write the release itself");
        assertEquals(null, lock.record.getHolderIdentity());
    }


    /**
     * A release that hangs in the API server does not delay close() beyond the close timeout.
     *
     * @throws Exception in case of an error
     */
    @Test
    public void testHangingReleaseDoesNotBlockClose() throws Exception {
        ControllableLock lock = new ControllableLock("hanging-release-node");
        LeaderElectionConfiguration config = new LeaderElectionConfiguration(Duration.ofSeconds(10), Duration.ofSeconds(7), Duration.ofSeconds(1));
        config.setCloseTimeout(Duration.ofSeconds(2));
        KubernetesLeaderElectorImpl elector = createElector(lock, "k8s-recovery-hanging-release", config);
        assertTrue(elector.elect(Duration.ofSeconds(10)));
        lock.hangRelease = new CountDownLatch(1);
        try {
            long start = System.nanoTime();
            elector.close();
            long durationMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
            assertTrue(durationMillis < 4000, "close() must not wait for the hanging release, took " + durationMillis + " ms");
            assertFalse(elector.isLeader());
        } finally {
            lock.hangRelease.countDown();
        }
    }


    /**
     * Create an elector on the given lock.
     *
     * @param lock the lock
     * @param name the election name
     * @param config the configuration
     * @return the elector
     */
    private static KubernetesLeaderElectorImpl createElector(ControllableLock lock, String name, LeaderElectionConfiguration config) {
        return new KubernetesLeaderElectorImpl(new LeaderElectionInformation(null, name, lock.identity()), config, new ApiClient()) {
            /**
             * @see com.github.toolarium.leader.election.impl.kubernetes.KubernetesLeaderElectorImpl#createLock(com.github.toolarium.leader.election.dto.LeaderElectionInformation)
             */
            @Override
            protected Lock createLock(LeaderElectionInformation leaderElectionInformation) {
                return lock;
            }
        };
    }


    /**
     * Wait until a condition is true.
     *
     * @param condition the condition
     * @param timeoutMillis the maximum time to wait
     * @return true if the condition is true
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
     * An in-memory lock of one node whose reads, renewals and release can fail or hang.
     */
    private static final class ControllableLock implements Lock {
        private final String identity;
        private final AtomicInteger failingReads = new AtomicInteger();
        private final AtomicInteger releases = new AtomicInteger();
        private final AtomicInteger rejectedReleases = new AtomicInteger();
        private volatile CountDownLatch hangRelease;
        private volatile CountDownLatch hangUpdates;
        private volatile LeaderElectionRecord record;
        private volatile boolean failUpdates;
        private volatile boolean failRelease;


        /**
         * Constructor for ControllableLock
         *
         * @param identity the identity of the node
         */
        ControllableLock(String identity) {
            this.identity = identity;
        }


        /**
         * @see io.kubernetes.client.extended.leaderelection.Lock#get()
         */
        @Override
        public LeaderElectionRecord get() {
            if (failingReads.getAndUpdate(n -> Math.max(0, n - 1)) > 0) {
                throw new IllegalStateException("Simulated read failure");
            }
            return record;
        }


        /**
         * @see io.kubernetes.client.extended.leaderelection.Lock#create(io.kubernetes.client.extended.leaderelection.LeaderElectionRecord)
         */
        @Override
        public synchronized boolean create(LeaderElectionRecord newRecord) {
            if (record != null) {
                return false;
            }
            record = newRecord;
            return true;
        }


        /**
         * @see io.kubernetes.client.extended.leaderelection.Lock#update(io.kubernetes.client.extended.leaderelection.LeaderElectionRecord)
         */
        @Override
        public boolean update(LeaderElectionRecord newRecord) {
            if (newRecord.getHolderIdentity() == null) {
                CountDownLatch hang = hangRelease;
                if (hang != null) {
                    try {
                        hang.await();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                    return false;
                }
                if (rejectedReleases.getAndUpdate(n -> Math.max(0, n - 1)) > 0) {
                    return false;
                }
                if (failRelease) {
                    throw new IllegalStateException("Simulated release failure");
                }
                releases.incrementAndGet();
            } else if (failUpdates) {
                return false;
            } else if (hangUpdates != null) {
                try {
                    hangUpdates.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                return false;
            }
            synchronized (this) {
                record = newRecord;
            }
            return true;
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
            return "controllable-lock/" + identity;
        }
    }
}
