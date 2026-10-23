/*
 * AbstractLeaderElectorImpl.java
 *
 * Copyright by toolarium, all rights reserved.
 */
package com.github.toolarium.leader.election.impl;

import com.github.toolarium.leader.election.ILeaderElector;
import com.github.toolarium.leader.election.ILeaderElectorView;
import com.github.toolarium.leader.election.ILeaderTask;
import com.github.toolarium.leader.election.dto.LeaderElectionConfiguration;
import com.github.toolarium.leader.election.dto.LeaderElectionInformation;
import com.github.toolarium.leader.election.exception.LeaderElectionException;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


/**
 * Base class for leader elector implementations
 *
 * @author patrick
 */
public abstract class AbstractLeaderElectorImpl<T extends LeaderElectionConfiguration> implements ILeaderElector, ILeaderElectorView {
    private static final Logger LOG = LoggerFactory.getLogger(AbstractLeaderElectorImpl.class);
    private final String id;
    private final LeaderElectionInformation leaderElectionInformation;
    private final T leaderElectionConfiguration;
    private volatile Boolean isLeader;
    private volatile Instant timestamp;
    private volatile String description;
    private volatile boolean isInitialized;
    private volatile CountDownLatch electionLatch;
    private volatile boolean electionClosed;
    private final AtomicLong leadershipTerm;
    private final ThreadLocal<Boolean> extendingInProgress;
    private final Map<String, ReentrantLock> taskLocks;
    private final LeaderLeaseMonitor leaseMonitor;
    private final ReadWriteLock lifecycleLock;


    /**
     * Constructor for AbstractLeaderElectorImpl
     *
     * @param leaderElectionInformation the leader election information
     * @param leaderElectionConfiguration the leader election configuration
     */
    public AbstractLeaderElectorImpl(LeaderElectionInformation leaderElectionInformation, T leaderElectionConfiguration) {
        this.id = UUID.randomUUID().toString();
        this.leaderElectionInformation = leaderElectionInformation;
        this.leaderElectionConfiguration = leaderElectionConfiguration;
        isLeader = null;
        description = null;
        isInitialized = false;
        electionLatch = new CountDownLatch(1);
        electionClosed = false;
        leadershipTerm = new AtomicLong(0);
        extendingInProgress = ThreadLocal.withInitial(() -> Boolean.FALSE);
        taskLocks = new ConcurrentHashMap<>();
        leaseMonitor = new LeaderLeaseMonitor();
        lifecycleLock = new ReentrantReadWriteLock();
    }


    /**
     * @see com.github.toolarium.leader.election.ILeaderElector#getId()
     */
    @Override
    public String getId() {
        return id;
    }


    /**
     * @see com.github.toolarium.leader.election.ILeaderElector#elect()
     */
    @Override
    public boolean elect() throws LeaderElectionException, InterruptedException {
        startElectionIfNeeded();
        electionLatch.await();
        return isLeader();
    }


    /**
     * @see com.github.toolarium.leader.election.ILeaderElector#elect(java.time.Duration)
     */
    @Override
    public boolean elect(Duration timeout) throws LeaderElectionException, InterruptedException {
        if (timeout == null) {
            return elect();
        }
        startElectionIfNeeded();
        electionLatch.await(timeout.toMillis(), TimeUnit.MILLISECONDS);
        return isLeader();
    }


    /**
     * Check if it is initialized
     *
     * @return true if it is initialized
     */
    protected boolean isInitialized() {
        return isInitialized;
    }


    /**
     * Return the current leadership term counter. The counter is incremented each time this
     * node transitions from non-leader to leader. A {@link DefaultLeaderLease} captures the
     * term at creation; {@link DefaultLeaderLease#isValid()} returns {@code false} once the
     * term no longer matches, preventing a lease from a previous term from becoming valid again
     * after the elector re-acquires leadership.
     *
     * @return the current leadership term
     */
    long getLeadershipTerm() {
        return leadershipTerm.get();
    }


    /**
     * Returns {@code true} once at least one call to {@link #setLeader} has been made
     * (isLeader is not null). Used by subclasses to decide whether {@link #elect()} needs
     * to be unblocked with a "not leader" result on the first election round failure.
     *
     * @return true if a leader election result has been established
     */
    protected boolean hasElectionResult() {
        return isLeader != null;
    }


    /**
     * @see com.github.toolarium.leader.election.ILeaderElector#isLeader()
     */
    @Override
    public boolean isLeader() {
        Boolean current = isLeader;
        return current != null && current.booleanValue();
    }


    /**
     * @see com.github.toolarium.leader.election.ILeaderElector#getLeaderElectorTimestamp()
     */
    @Override
    public Instant getLeaderElectorTimestamp() {
        return timestamp;
    }


    /**
     * @see com.github.toolarium.leader.election.ILeaderElector#close()
     */
    @Override
    public void close() throws LeaderElectionException {
        closeInternal(leaderElectionConfiguration.getCloseTimeout().toMillis());
    }


    /**
     * Shared close logic used by {@link #close()} and by subclasses that compute a single
     * close deadline across several steps. Subclasses should call this instead of
     * {@link #close()} when they have already spent part of the close budget waiting for
     * their own resources (e.g. a scheduler executor) so that the total close time does
     * not exceed the configured {@code closeTimeout}.
     *
     * @param writeLockTimeoutMillis maximum milliseconds to wait for the lifecycle write lock
     * @throws LeaderElectionException if cleanup fails
     */
    void closeInternal(long writeLockTimeoutMillis) throws LeaderElectionException {
        boolean wasLeader = Boolean.TRUE.equals(isLeader);

        // Signal any running tasks: setting isLeader=null makes isValid() return false,
        // so the LeaderLeaseMonitor will interrupt them on its next check (within 100 ms).
        isLeader = null;
        timestamp = null;
        electionClosed = true;

        // Wait for all running runIfLeader tasks to finish (they hold read locks).
        // The monitor interrupts them within CHECK_PERIOD_MILLIS; the write lock is
        // attempted with a short spin so close() does not block indefinitely when
        // all tasks finish quickly (common case), but proceeds anyway if the timeout
        // elapses (tasks will still be interrupted by the monitor).
        Lock writeLock = lifecycleLock.writeLock();
        boolean locked = false;
        try {
            locked = writeLock.tryLock(Math.max(0L, writeLockTimeoutMillis), TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        try {
            if (wasLeader) {
                LOG.info("Released lead of [{}].", leaderElectionInformation.getUniqueName());
            }
            LeaderElectionScheduler.getInstance().unregister(this);
            leaseMonitor.stop();
            description = null;
            isInitialized = false;
            electionLatch.countDown();           // unblock any elect() waiting on the old latch
            electionLatch = new CountDownLatch(1);
        } finally {
            if (locked) {
                writeLock.unlock();
            }
        }
    }


    /**
     * @see com.github.toolarium.leader.election.ILeaderElector#runIfLeader(String, ILeaderTask)
     */
    @Override
    public boolean runIfLeader(String name, ILeaderTask task) throws LeaderElectionException {
        if (!isLeader()) {
            return false;
        }

        // Hold a read lock for the duration of the task.
        // close() acquires the write lock, which blocks until all read locks are released.
        Lock readLock = lifecycleLock.readLock();
        if (!readLock.tryLock()) {
            return false;
        }

        try {
            // Re-check under lock: close() may have set isLeader=null between the check above and tryLock().
            if (!isLeader()) {
                return false;
            }

            ReentrantLock taskLock = taskLocks.computeIfAbsent(name, k -> new ReentrantLock());
            if (!taskLock.tryLock()) {
                if (LOG.isDebugEnabled()) {
                    LOG.debug("runIfLeader [{}]: task already running, skipping.", name);
                }
                return false;
            }

            DefaultLeaderLease lease = new DefaultLeaderLease(this, leaderElectionInformation, leaderElectionConfiguration, Thread.currentThread(), getLeadershipTerm());
            leaseMonitor.register(lease);

            try {
                if (LOG.isDebugEnabled()) {
                    LOG.debug("runIfLeader [{}]: starting.", name);
                }
                task.run(lease);
                return true;
            } catch (InterruptedException e) {
                // Catching InterruptedException clears the flag. Re-set it for non-monitor
                // interrupts so the finally block can decide whether to forward it to the caller.
                if (!lease.wasInterruptedByMonitor()) {
                    Thread.currentThread().interrupt();
                }
                if (LOG.isDebugEnabled()) {
                    LOG.debug("runIfLeader [{}]: interrupted (leadership lost or elector closed).", name);
                }
                return true;
            } catch (RuntimeException e) {
                throw e;
            } catch (Exception e) {
                throw new LeaderElectionException("Task '" + name + "' failed: " + e.getMessage(), e);
            } finally {
                // Unregister FIRST so the monitor stops watching this lease.
                // Then clear-and-conditionally-restore the interrupt flag:
                // Thread.interrupted() atomically clears the flag and returns the old value.
                // If it was set and did NOT come from the monitor, restore it so the
                // caller sees a legitimate external interrupt.
                // Placing this after unregister closes the race where the monitor fires
                // just after task.run() returns and sets interruptedByMonitor=true.
                leaseMonitor.unregister(lease);
                // Synchronize on the lease to close two races:
                // 1. The monitor has already read the lease from its snapshot (before unregister),
                //    called isValid()=false, and is about to call interruptIfRunning(). Without
                //    markFinished() the monitor would interrupt the caller's thread AFTER the task
                //    is done, corrupting the interrupt state for the next unrelated job.
                // 2. The monitor may have set interruptedByMonitor=true but not yet called
                //    taskThread.interrupt(). Thread.interrupted() below clears the flag
                //    atomically; markFinished() ensures no late interrupt follows.
                synchronized (lease) {
                    lease.markFinished();
                    boolean wasInterrupted = Thread.interrupted();
                    if (wasInterrupted && !lease.wasInterruptedByMonitor()) {
                        Thread.currentThread().interrupt();
                    }
                }
                taskLock.unlock();
                // Remove the entry when no thread is holding the lock to prevent
                // unbounded map growth with many distinct task names.
                taskLocks.computeIfPresent(name, (k, lock) -> {
                    if (lock.isLocked()) {
                        return lock;
                    }
                    return null;
                });
                if (LOG.isDebugEnabled()) {
                    LOG.debug("runIfLeader [{}]: finished.", name);
                }
            }
        } finally {
            readLock.unlock();
        }
    }


    /**
     * Initialise the leader elector implementation. Called at most once per lifecycle (between
     * construction / close and the next elect() call).
     *
     * @throws LeaderElectionException In case of an initialisation error
     */
    protected abstract void initializeImplementation() throws LeaderElectionException;


    /**
     * Attempt to extend the leadership lease immediately, pushing the renew deadline further out.
     * The default implementation is a no-op that returns {@link #isLeader()}; strategies with an
     * explicit lease timestamp (DATABASE, FILE) override this to perform the actual extension.
     *
     * @return {@code true} if this node is still the leader after the extension attempt
     * @throws LeaderElectionException if the extension attempt fails with an unrecoverable error
     */
    protected boolean extendLeaseNow() throws LeaderElectionException {
        return isLeader();
    }


    /**
     * Wrapper called by {@link DefaultLeaderLease#tryExtend()} that sets the
     * {@code extendingInProgress} flag for the duration of the extension. This prevents
     * the leadership-term counter from being incremented by any {@code setLeader(true)}
     * calls that happen as a side-effect of the extension (e.g. the FILE strategy's
     * {@code FileLock.renew()} fires {@code releaseLock()} + {@code hasLock()} callbacks
     * internally — those must not count as a real leader-lost / leader-gained transition).
     *
     * @return {@code true} if this node is still the leader after the extension attempt
     * @throws LeaderElectionException if the extension attempt fails with an unrecoverable error
     */
    boolean extendLease() throws LeaderElectionException {
        extendingInProgress.set(Boolean.TRUE);
        try {
            return extendLeaseNow();
        } finally {
            extendingInProgress.set(Boolean.FALSE);
        }
    }


    /**
     * Set the leader
     *
     * @param isLeader the leader
     * @param inputDescription the description
     */
    protected synchronized void setLeader(final Boolean isLeader, final String inputDescription) {
        String newDescription;
        if (inputDescription != null) {
            newDescription = " (" + inputDescription + ").";
        } else {
            newDescription = ".";
        }

        if (isLeader == null) {
            // unset leader
            if (this.isLeader != null && this.isLeader) {
                LOG.info("Lost lead of [{}]{}", leaderElectionInformation.getUniqueName(), newDescription);
            }

            this.timestamp = null;
        } else if (isLeader.booleanValue()) {
            // I'm the leader — update timestamp on every confirmation so getRemainingLeadTime()
            // reflects the last renewal/extension rather than only the initial acquisition.
            if (this.isLeader == null || !this.isLeader) {
                if (!extendingInProgress.get()) {
                    // Real acquisition: increment the term so leases from previous terms
                    // cannot become valid again after a lead loss + re-gain.
                    leadershipTerm.incrementAndGet();
                    LOG.info("Gained lead of [{}]{}", leaderElectionInformation.getUniqueName(), newDescription);
                }
                // If extendingInProgress, we're inside tryExtend() — the unlock+lock cycle of
                // e.g. FileLock.renew() fires releaseLock+hasLock callbacks but the node never
                // truly lost leadership, so the term must not increment.
            }
            this.timestamp = Instant.now();
        } else {
            // No longer leader — the LeaderLeaseMonitor will detect isValid()==false on its
            // next 100 ms tick and interrupt any running tasks; no direct interrupt here.
            if (this.isLeader != null && this.isLeader) {
                LOG.info("Lost lead of [{}]{}", leaderElectionInformation.getUniqueName(), newDescription);
            }
            this.timestamp = null;
        }

        this.isLeader = isLeader;
        this.description = newDescription;
        if (!electionClosed && isElectionResultDefinitive(Boolean.TRUE.equals(isLeader))) {
            electionLatch.countDown();
        }
    }


    /**
     * Returns {@code true} if the given leader-election result should unblock callers waiting
     * on {@link #elect()} / {@link #elect(java.time.Duration)}. The default implementation
     * always returns {@code true}; subclasses may override this to suppress premature latch
     * release when an early result is not yet reliable (e.g. the NETWORK strategy suppresses
     * "not leader" results during the JGroups phantom-member stabilisation window).
     *
     * @param isLeaderResult {@code true} if the current result is "this node is leader"
     * @return {@code true} to count down the election latch, {@code false} to skip
     */
    protected boolean isElectionResultDefinitive(boolean isLeaderResult) {
        return true;
    }


    /**
     * @see com.github.toolarium.leader.election.ILeaderElectorView#getLeaderElectionInformation()
     */
    @Override
    public LeaderElectionInformation getLeaderElectionInformation() {
        return leaderElectionInformation;
    }


    /**
     * @see com.github.toolarium.leader.election.ILeaderElectorView#getLeaderElectionConfiguration()
     */
    @Override
    public T getLeaderElectionConfiguration() {
        return leaderElectionConfiguration;
    }


    /**
     * Start the election machinery if it has not been started yet (idempotent).
     * Uses the lifecycle write lock so that concurrent calls to {@link #close()} are
     * properly serialized with initialization.
     *
     * @throws LeaderElectionException In case of an initialisation error
     */
    private void startElectionIfNeeded() throws LeaderElectionException {
        // Fast-path: already initialized — no lock needed (volatile read).
        if (isInitialized) {
            return;
        }
        Lock writeLock = lifecycleLock.writeLock();
        writeLock.lock();
        try {
            // Re-check under lock: another thread may have initialized between the volatile read above and lock acquisition.
            if (!isInitialized) {
                electionClosed = false;
                leaseMonitor.start();
                initializeImplementation();
                isInitialized = true;
            }
        } finally {
            writeLock.unlock();
        }
    }
}
