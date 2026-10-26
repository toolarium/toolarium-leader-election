/*
 * KubernetesLeaderElectorImpl.java
 *
 * Copyright by toolarium, all rights reserved.
 */
package com.github.toolarium.leader.election.impl.kubernetes;

import com.github.toolarium.leader.election.ILeaderElector;
import com.github.toolarium.leader.election.dto.LeaderElectionConfiguration;
import com.github.toolarium.leader.election.dto.LeaderElectionInformation;
import com.github.toolarium.leader.election.dto.kubernetes.KubernetesLeaderElectionConfiguration;
import com.github.toolarium.leader.election.dto.kubernetes.KubernetesLockType;
import com.github.toolarium.leader.election.exception.LeaderElectionException;
import com.github.toolarium.leader.election.impl.AbstractLeaderElectorImpl;
import io.kubernetes.client.extended.leaderelection.LeaderElectionConfig;
import io.kubernetes.client.extended.leaderelection.LeaderElectionRecord;
import io.kubernetes.client.extended.leaderelection.Lock;
import io.kubernetes.client.extended.leaderelection.resourcelock.EndpointsLock;
import io.kubernetes.client.extended.leaderelection.resourcelock.LeaseLock;
import io.kubernetes.client.openapi.ApiClient;
import io.kubernetes.client.openapi.ApiException;
import io.kubernetes.client.util.Config;
import java.io.IOException;
import java.util.Date;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


/**
 * Implements the {@link ILeaderElector} based on kubernetes api.
 *
 * <p>The lock is a {@code coordination.k8s.io/v1} Lease named after the election in its namespace. A
 * {@link KubernetesLeaderElectionConfiguration} can select the Endpoints lock of release 1.2.x instead,
 * for a rolling upgrade.
 *
 * @author patrick
 */
public class KubernetesLeaderElectorImpl extends AbstractLeaderElectorImpl<LeaderElectionConfiguration> {
    private static final Logger LOG = LoggerFactory.getLogger(KubernetesLeaderElectorImpl.class);
    private static final long NANOS_NOT_SET = Long.MIN_VALUE;
    // volatile: written by elect() (initialization thread) and read by close() from another thread,
    // e.g. the JVM shutdown hook.
    private volatile io.kubernetes.client.extended.leaderelection.LeaderElector leaderElector;
    private volatile Lock lock;
    private volatile Thread updateThread;
    // Per session: the update thread runs while runFlag is set; the client callbacks report to this elector while activeFlag
    // is set. A thread or callback of a previous session therefore never affects a later session.
    private volatile AtomicBoolean runFlag;
    private volatile AtomicBoolean activeFlag;
    // nanoTime of the start of the last successful acquisition or renewal of the lock by this node
    private volatile long lastRenewalNanos = NANOS_NOT_SET;
    private final AtomicBoolean closing = new AtomicBoolean(false);
    private final ApiClient preConfiguredClient;
    private volatile ApiClient resolvedClient;


    /**
     * Constructor for KubernetesLeaderElectorImpl
     *
     * @param leaderElectionInformation the leader election information
     * @param leaderElectionConfiguration the leader election configuration
     */
    public KubernetesLeaderElectorImpl(LeaderElectionInformation leaderElectionInformation, LeaderElectionConfiguration leaderElectionConfiguration) {
        this(leaderElectionInformation, leaderElectionConfiguration, null);
    }


    /**
     * Constructor for KubernetesLeaderElectorImpl with a pre-configured API client.
     *
     * @param leaderElectionInformation the leader election information
     * @param leaderElectionConfiguration the leader election configuration
     * @param apiClient an already-configured Kubernetes API client, or null to use the default
     */
    public KubernetesLeaderElectorImpl(LeaderElectionInformation leaderElectionInformation, LeaderElectionConfiguration leaderElectionConfiguration, ApiClient apiClient) {
        super(leaderElectionInformation, leaderElectionConfiguration);
        this.preConfiguredClient = apiClient;
        this.resolvedClient = null;
        leaderElector = null;
        updateThread = null;
        runFlag = null;
        activeFlag = null;
    }

    
    /**
     * @see com.github.toolarium.leader.election.impl.AbstractLeaderElectorImpl#initializeImplementation()
     */
    @Override
    protected void initializeImplementation() throws LeaderElectionException {
        closing.set(false);

        if (LOG.isDebugEnabled()) {
            LOG.debug("Initialize kubernetes api client...");
        }

        try {
            if (preConfiguredClient != null) {
                resolvedClient = preConfiguredClient;
            } else {
                resolvedClient = Config.defaultClient();
            }
        } catch (IOException e) {
            throw new LeaderElectionException("Could not initialize the kubernetes api client: " + e.getMessage(), e);
        }

        lastRenewalNanos = NANOS_NOT_SET;
        final Lock newLock = new RenewalTrackingLock(createLock(getLeaderElectionInformation()));
        // The update thread works on these local references: close() nulls the fields concurrently.
        final io.kubernetes.client.extended.leaderelection.LeaderElector elector =
                new io.kubernetes.client.extended.leaderelection.LeaderElector(new LeaderElectionConfig(newLock, getLeaderElectionConfiguration().getTimeout(),
                                                                                   getLeaderElectionConfiguration().getRenewDeadline(),
                                                                                   getLeaderElectionConfiguration().getRetryPeriod()));
        lock = newLock;
        leaderElector = elector;

        // The holder identity reported to onNewLeader is the identity of the lock (not necessarily
        // LeaderElectionInformation.getIdentity(), e.g. for a custom createLock()).
        final String identity = newLock.identity();
        final AtomicBoolean running = new AtomicBoolean(true);
        final AtomicBoolean active = new AtomicBoolean(true);
        final Thread thread = new Thread(KubernetesLeaderElectorImpl.class.getName() + ": Update thread") {
            /**
             * @see java.lang.Thread#run()
             */
            @Override
            public void run() {
                // an Error is warned about once until an election run ends normally again, so a lasting problem does not flood the log
                boolean errorReported = false;
                while (running.get() && !Thread.currentThread().isInterrupted()) {
                    try {
                        runElection(elector, identity, active);
                        errorReported = false;
                    } catch (Throwable e) {
                        if (!running.get()) {
                            // close() shut down the elector's executors while run() was active.
                            break;
                        }
                        if (LOG.isDebugEnabled()) {
                            LOG.debug("Kubernetes leader election of [{}] failed: {}", getLeaderElectionInformation().getUniqueName(), e.toString(), e);
                        }
                        // An Error does not end the thread either: the node would never compete again.
                        if (e instanceof Error && !errorReported) {
                            errorReported = true;
                            LOG.warn("Kubernetes leader election of [{}] failed: {}. The node cannot lead while this lasts; check the memory and the classpath, the debug log has the stack trace.", 
                                     getLeaderElectionInformation().getUniqueName(), e.toString());
                        } else if (!(e instanceof Error)) {
                            LOG.info("Kubernetes leader election of [{}] failed: {}, will retry after the retry period.", getLeaderElectionInformation().getUniqueName(), e.getMessage());
                        }
                    }
                    if (running.get() && !Thread.currentThread().isInterrupted()) {
                        try {
                            Thread.sleep(getLeaderElectionConfiguration().getRetryPeriod().toMillis());
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                        }
                    }
                }

                if (LOG.isDebugEnabled()) {
                    LOG.debug(KubernetesLeaderElectorImpl.class.getName() + " thread stopped successful.");
                }
            }
        };

        runFlag = running;
        activeFlag = active;
        thread.setDaemon(true);
        thread.setName(KubernetesLeaderElectorImpl.class.getName() + ": Update thread");
        updateThread = thread;
        thread.start();
    }


    /**
     * Run one leader election cycle; returns when leadership is lost or the elector is closed.
     *
     * <p>The client runs the start hook asynchronously but the stop hook inline, so after a fast loss the start hook can run
     * after the stop hook. Both hooks of a cycle are therefore ordered by a lock, and a start after the stop of the same cycle
     * is ignored. Hooks of a closed session ({@code active} cleared) do not report anymore.
     *
     * @param elector the kubernetes leader elector
     * @param identity the identity of this node's lock
     * @param active true while the hooks report to this elector
     */
    private void runElection(io.kubernetes.client.extended.leaderelection.LeaderElector elector, String identity, AtomicBoolean active) {
        final Object cycleLock = new Object();
        final AtomicBoolean stopped = new AtomicBoolean(false);
        elector.run(() -> {
            if (LOG.isDebugEnabled()) {
                LOG.debug("LEAD");
            }
            synchronized (cycleLock) {
                if (active.get() && !stopped.get()) {
                    setLeader(true, null);
                }
            }
        }, () -> {
            if (LOG.isDebugEnabled()) {
                LOG.debug("NO LEAD");
            }
            synchronized (cycleLock) {
                stopped.set(true);
                if (active.get()) {
                    setLeader(false, null);
                }
            }
        }, newLeader -> {
            // The start/stop hooks only fire once this node acquires the lock, so a follower
            // would never produce an election result and elect() without a timeout would
            // block forever. Report "not leader" as soon as another identity holds the lock.
            if (active.get() && newLeader != null && !newLeader.equals(identity)) {
                if (LOG.isDebugEnabled()) {
                    LOG.debug("NEW LEADER {}", newLeader);
                }
                setLeader(false, newLeader);
            }
        });
    }


    /**
     * @see com.github.toolarium.leader.election.ILeaderElector#isLeader()
     *     The lead only counts while the lock was acquired or renewed within the renew deadline: the client keeps reporting the
     *     lead until its renew loop gives up, which with valid timings can be later than the point at which another node may
     *     take the lock over.
     */
    @Override
    public boolean isLeader() {
        if (!super.isLeader()) {
            return false;
        }
        long last = lastRenewalNanos;
        return last != NANOS_NOT_SET && System.nanoTime() - last <= getLeaderElectionConfiguration().getRenewDeadline().toNanos();
    }


    /**
     * Create the lock: a Lease, or the Endpoints lock when a {@link KubernetesLeaderElectionConfiguration}
     * selects {@link KubernetesLockType#ENDPOINTS}.
     *
     * @param leaderElectionInformation the leader election information
     * @return the lock
     */
    protected Lock createLock(LeaderElectionInformation leaderElectionInformation) {
        String namespace = leaderElectionInformation.getNamespace();
        String name = leaderElectionInformation.getName();
        String identity = leaderElectionInformation.getIdentity();
        if (getLockType() == KubernetesLockType.ENDPOINTS) {
            return new EndpointsLock(namespace, name, identity, resolvedClient);
        }
        return new LeaseLock(namespace, name, identity, resolvedClient);
    }


    /**
     * Gets the lock type: the one of a {@link KubernetesLeaderElectionConfiguration}, otherwise the default.
     *
     * @return the lock type
     */
    public KubernetesLockType getLockType() {
        if (getLeaderElectionConfiguration() instanceof KubernetesLeaderElectionConfiguration) {
            return ((KubernetesLeaderElectionConfiguration) getLeaderElectionConfiguration()).getLockType();
        }
        return KubernetesLeaderElectionConfiguration.DEFAULT_LOCK_TYPE;
    }


    /**
     * @see com.github.toolarium.leader.election.ILeaderElector#close()
     */
    @Override
    public void close() throws LeaderElectionException {
        closeSerialized(getLeaderElectionConfiguration().getCloseTimeout().toMillis(), this::closeNow);
    }


    /**
     * Close the elector: the client keeps renewing the lock while leader tasks still run, so that no other node takes the lead
     * meanwhile; once they ended, the client is stopped and the lock is released.
     *
     * @throws LeaderElectionException in case the update thread does not end within the close timeout
     */
    private void closeNow() throws LeaderElectionException {
        if (!closing.compareAndSet(false, true)) {
            return;
        }

        final AtomicBoolean active = activeFlag;
        if (active != null) {
            active.set(false);
        }
        final AtomicBoolean running = runFlag;
        final Thread thread = updateThread;
        updateThread = null;
        final boolean wasLeader = isLeader();
        final io.kubernetes.client.extended.leaderelection.LeaderElector elector = leaderElector;
        leaderElector = null;
        final Lock lockToRelease = lock;
        lock = null;

        boolean tasksFinished = false;
        try {
            tasksFinished = closeAndAwaitTasks();
        } finally {
            if (!tasksFinished) {
                releaseWhenTasksFinished(false, "leader-election-k8s-release-" + getLeaderElectionInformation().getName(),
                                         () -> stopAndRelease(running, elector, thread, lockToRelease, wasLeader));
            }
        }

        if (tasksFinished && stopAndRelease(running, elector, thread, lockToRelease, wasLeader)) {
            throw new LeaderElectionException(
                    "Kubernetes leader elector thread [" + thread.getName() + "] did not terminate within "
                    + getLeaderElectionConfiguration().getCloseTimeout().toMillis() + " ms. Increase closeTimeout in LeaderElectionConfiguration "
                    + "or investigate why the Kubernetes client is not responding to close().");
        }
    }


    /**
     * Stop the client and the update thread within the close timeout, then release the lock.
     *
     * @param running the run flag of the update thread
     * @param elector the kubernetes leader elector or null
     * @param thread the update thread or null
     * @param lockToRelease the lock or null
     * @param wasLeader true if this node led when it was closed
     * @return true if the update thread is still alive after the close timeout
     */
    private boolean stopAndRelease(AtomicBoolean running, io.kubernetes.client.extended.leaderelection.LeaderElector elector, Thread thread,
                                   Lock lockToRelease, boolean wasLeader) {
        if (running != null) {
            running.set(false);
        }
        long deadlineNanos = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(getLeaderElectionConfiguration().getCloseTimeout().toMillis());
        if (elector != null) {
            LOG.info("Exited from [{}].", getLeaderElectionInformation().getUniqueName());
            closeElector(elector, deadlineNanos);
        }

        boolean threadStillAlive = false;
        if (thread != null) {
            thread.interrupt();
            try {
                // join(0) would wait forever: wait at least 1 ms
                thread.join(Math.max(1L, TimeUnit.NANOSECONDS.toMillis(deadlineNanos - System.nanoTime())));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            threadStillAlive = thread.isAlive();
        }

        // Release the lock only once the renew loop is stopped, otherwise a last renewal could
        // overwrite the release. Without the release the next leader waits the full lease duration.
        if (wasLeader && lockToRelease != null && !threadStillAlive) {
            releaseLock(lockToRelease, TimeUnit.NANOSECONDS.toMillis(deadlineNanos - System.nanoTime()));
        }
        return threadStillAlive;
    }


    /**
     * Close the kubernetes leader elector within the close budget. The client waits for its executors and gives up the lock
     * with a call to the API server that has no timeout of its own, so it runs on a daemon thread; if it does not end in time,
     * close() continues and the lease expires after the configured timeout.
     *
     * @param elector the kubernetes leader elector
     * @param deadlineNanos the end of the close budget ({@link System#nanoTime()})
     */
    private void closeElector(io.kubernetes.client.extended.leaderelection.LeaderElector elector, long deadlineNanos) {
        Thread closeThread = new Thread(() -> {
            try {
                elector.close();
            } catch (Exception e) {
                // NOP
            }
        }, "leader-election-k8s-close-" + getLeaderElectionInformation().getName());
        closeThread.setDaemon(true);
        closeThread.start();
        try {
            closeThread.join(Math.max(1L, TimeUnit.NANOSECONDS.toMillis(deadlineNanos - System.nanoTime())));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        if (closeThread.isAlive()) {
            LOG.info("Kubernetes leader elector of [{}] did not close before the close timeout; the lease will expire after the configured timeout.",
                     getLeaderElectionInformation().getUniqueName());
        }
    }


    /**
     * Release the lock if this node still holds it, so that another node can take over on its next
     * retry instead of waiting for the lease to expire. The kubernetes client gives up the lock itself
     * when it is closed; this is the fallback when that failed or did not end in time. A record without holder identity is taken over
     * immediately by the Java client; the Go client does the same for an empty holder, and the lease
     * duration of 1 s covers clients that only honour the expiry. Runs on a daemon thread bounded by
     * the remaining close budget; on failure the lease simply expires.
     *
     * @param lockToRelease the lock
     * @param timeoutMillis the maximum time to wait for the release
     */
    private void releaseLock(Lock lockToRelease, long timeoutMillis) {
        final String uniqueName = getLeaderElectionInformation().getUniqueName();
        Thread releaseThread = new Thread(() -> {
            try {
                LeaderElectionRecord current = lockToRelease.get();
                if (current == null || !lockToRelease.identity().equals(current.getHolderIdentity())) {
                    return; // another node already holds the lock
                }
                Date now = new Date();
                LeaderElectionRecord released = new LeaderElectionRecord(null, 1, now, now, current.getLeaderTransitions());
                if (lockToRelease.update(released)) {
                    if (LOG.isDebugEnabled()) {
                        LOG.debug("Released kubernetes lock of [{}].", uniqueName);
                    }
                }
            } catch (Exception e) {
                LOG.info("Could not release kubernetes lock of [{}]: {}, the lease will expire after the configured timeout.", uniqueName, e.getMessage());
                if (LOG.isDebugEnabled()) {
                    LOG.debug("Error detail:", e);
                }
            }
        }, "leader-election-k8s-release-" + getLeaderElectionInformation().getName());
        releaseThread.setDaemon(true);
        releaseThread.start();
        if (timeoutMillis > 0) {
            try {
                releaseThread.join(timeoutMillis);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        if (releaseThread.isAlive()) {
            LOG.info("Kubernetes lock of [{}] could not be released before the close timeout; the lease will expire after the configured timeout.", uniqueName);
        }
    }


    /**
     * A lock that records when this node acquired or renewed it, for the renew deadline check of {@link #isLeader()}.
     */
    private final class RenewalTrackingLock implements Lock {
        private final Lock delegate;


        /**
         * Constructor for RenewalTrackingLock
         *
         * @param delegate the lock
         */
        RenewalTrackingLock(Lock delegate) {
            this.delegate = delegate;
        }


        /**
         * @see io.kubernetes.client.extended.leaderelection.Lock#get()
         */
        @Override
        public LeaderElectionRecord get() throws ApiException {
            return delegate.get();
        }


        /**
         * @see io.kubernetes.client.extended.leaderelection.Lock#create(io.kubernetes.client.extended.leaderelection.LeaderElectionRecord)
         */
        @Override
        public boolean create(LeaderElectionRecord record) {
            long startNanos = System.nanoTime();
            boolean created = delegate.create(record);
            recordRenewal(created, record, startNanos);
            return created;
        }


        /**
         * @see io.kubernetes.client.extended.leaderelection.Lock#update(io.kubernetes.client.extended.leaderelection.LeaderElectionRecord)
         */
        @Override
        public boolean update(LeaderElectionRecord record) {
            long startNanos = System.nanoTime();
            boolean updated = delegate.update(record);
            recordRenewal(updated, record, startNanos);
            return updated;
        }


        /**
         * @see io.kubernetes.client.extended.leaderelection.Lock#identity()
         */
        @Override
        public String identity() {
            return delegate.identity();
        }


        /**
         * @see io.kubernetes.client.extended.leaderelection.Lock#describe()
         */
        @Override
        public String describe() {
            return delegate.describe();
        }


        /**
         * Record a successful acquisition or renewal by this node; a release (no holder) is not one.
         *
         * @param success true if the write succeeded
         * @param record the written record
         * @param startNanos the nanoTime before the write
         */
        private void recordRenewal(boolean success, LeaderElectionRecord record, long startNanos) {
            if (success && record != null && delegate.identity().equals(record.getHolderIdentity())) {
                lastRenewalNanos = startNanos;
            }
        }
    }
}
