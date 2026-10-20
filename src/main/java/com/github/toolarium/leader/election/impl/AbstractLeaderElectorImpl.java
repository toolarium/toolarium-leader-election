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
     * @see com.github.toolarium.leader.election.ILeaderElector#isLeader()
     */
    @Override
    public boolean isLeader() {
        if (isLeader != null) {
            if (isLeader.booleanValue()) {
                return true;
            }

            return false;
        }

        return false;
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
        long deadline = System.currentTimeMillis() + leaderElectionConfiguration.getCloseTimeout().toMillis();
        while (!locked && System.currentTimeMillis() < deadline) {
            locked = writeLock.tryLock();
            if (!locked) {
                try {
                    Thread.sleep(10);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }

        try {
            if (wasLeader && LOG.isDebugEnabled()) {
                LOG.debug("Release lead of [{}]{}", leaderElectionInformation.getUniqueName(), description);
            }
            LeaderElectionScheduler.getInstance().unregister(this);
            description = null;
            isInitialized = false;
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
                LOG.warn("runIfLeader [{}]: task already running, skipping.", name);
                return false;
            }

            DefaultLeaderLease lease = new DefaultLeaderLease(this, leaderElectionInformation, leaderElectionConfiguration, Thread.currentThread());
            leaseMonitor.register(lease);

            try {
                if (LOG.isDebugEnabled()) {
                    LOG.debug("runIfLeader [{}]: starting.", name);
                }
                task.run(lease);
                return true;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                LOG.debug("runIfLeader [{}]: interrupted (leadership lost or elector closed).", name);
                return true;
            } catch (Exception e) {
                throw new LeaderElectionException("Task '" + name + "' failed: " + e.getMessage(), e);
            } finally {
                leaseMonitor.unregister(lease);
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
     * Set the leader
     *
     * @param isLeader the leader
     * @param inputDescription the description
     */
    protected void setLeader(final Boolean isLeader, final String inputDescription) {
        String newDescription;
        if (inputDescription != null) {
            newDescription = " (" + inputDescription + ").";
        } else {
            newDescription = ".";
        }

        if (isLeader == null) {
            // unset leader
            if (this.isLeader != null && this.isLeader) {
                if (LOG.isDebugEnabled()) {
                    LOG.debug("Losed lead of [{}]{}", leaderElectionInformation.getUniqueName(), newDescription);
                }
            }

            this.timestamp = null;
        } else if (isLeader.booleanValue()) {
            // I'm the leader — update timestamp on every confirmation so getRemainingLeadTime()
            // reflects the last renewal/extension rather than only the initial acquisition.
            if (this.isLeader == null || !this.isLeader) {
                if (LOG.isDebugEnabled()) {
                    LOG.debug("Get in lead of [{}]{}", leaderElectionInformation.getUniqueName(), newDescription);
                }
            }
            this.timestamp = Instant.now();
        } else {
            // No longer leader — the LeaderLeaseMonitor will detect isValid()==false on its
            // next 100 ms tick and interrupt any running tasks; no direct interrupt here.
            this.timestamp = null;
        }

        this.isLeader = isLeader;
        this.description = newDescription;
        if (!electionClosed) {
            electionLatch.countDown();
        }
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
                initializeImplementation();
                isInitialized = true;
            }
        } finally {
            writeLock.unlock();
        }
    }
}
