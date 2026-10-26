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
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.Lock;
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
    // idle task locks are kept up to this number, so that a task run again and again reuses its lock
    private static final int MAX_IDLE_TASK_LOCKS = 64;
    // the deadline of an elect() without a timeout
    private static final long NO_DEADLINE = Long.MIN_VALUE;
    private final String id;
    private final LeaderElectionInformation leaderElectionInformation;
    private final T leaderElectionConfiguration;
    private volatile Boolean isLeader;
    private volatile Instant timestamp;
    // nanoTime of the last confirmation of the lead, see getLeadConfirmedNanos()
    private volatile long leadConfirmedNanos;
    // the fence time of the strategy (getLeadConfirmedNanos()) at the last confirmation; guarded by synchronized(this)
    private long confirmedFenceNanos;
    private volatile String description;
    private volatile boolean isInitialized;
    private volatile CountDownLatch electionLatch;
    private volatile boolean electionClosed;
    private final AtomicLong leadershipTerm;
    private final Map<String, ReentrantLock> taskLocks;
    private final LeaderLeaseMonitor leaseMonitor;
    private final ReentrantReadWriteLock lifecycleLock;
    private final ReentrantLock transitionLock;
    // a close() that could not wait for a running initialization; the initializing thread runs it once it has finished
    private final AtomicReference<CloseAction> pendingClose;
    // number of releases of closed sessions that wait for running leader tasks; elect() starts a new session only after them
    private final Object pendingReleaseMonitor;
    private int pendingReleases;


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
        taskLocks = new ConcurrentHashMap<>();
        leaseMonitor = new LeaderLeaseMonitor();
        lifecycleLock = new ReentrantReadWriteLock();
        transitionLock = new ReentrantLock();
        pendingClose = new AtomicReference<>();
        pendingReleaseMonitor = new Object();
        pendingReleases = 0;
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
        CountDownLatch latch = startElectionIfNeeded(NO_DEADLINE);
        latch.await();
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
        // Waiting for other threads is bounded by the timeout, too: e.g. a slow initialization on another thread, or a closed
        // session whose lock waits for a running task. The own initialization is not: the result is then awaited for the timeout.
        long timeoutNanos = toBoundedNanos(timeout);
        CountDownLatch latch = startElectionIfNeeded(System.nanoTime() + timeoutNanos);
        if (latch == null) {
            if (LOG.isDebugEnabled()) {
                LOG.debug("The election of [{}] could not be started within {} ms.", leaderElectionInformation.getUniqueName(), timeout.toMillis());
            }
            return isLeader();
        }
        latch.await(timeoutNanos, TimeUnit.NANOSECONDS);
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
     * @see com.github.toolarium.leader.election.ILeaderElectorView#getRemainingLeadTime()
     *     Measured with the monotonic clock from the same point in time the strategy fences its lead on, see
     *     {@code getLeadConfirmedNanos()}, so that it agrees with {@code isLeader()}.
     */
    @Override
    public Duration getRemainingLeadTime() {
        if (!isLeader()) {
            return Duration.ZERO;
        }
        // computed from one reading of the clock and never negative: the fence may pass right after the check above
        Duration remaining = leaderElectionConfiguration.getRenewDeadline().minusNanos(System.nanoTime() - getLeadConfirmedNanos());
        if (remaining.isNegative()) {
            return Duration.ZERO;
        }
        return remaining;
    }


    /**
     * Get the {@link System#nanoTime()} of the last confirmation of the lead. The default is the time the lead was last
     * reported; strategies that fence their lead on another point in time, e.g. the start of the renewal, return that one.
     *
     * @return the nanoTime of the last confirmation of the lead
     */
    protected long getLeadConfirmedNanos() {
        return leadConfirmedNanos;
    }


    /**
     * Immediately mark the leader state as invalid without acquiring any locks or waiting.
     * Called at the very start of a subclass {@code close()} so that the
     * {@link LeaderLeaseMonitor} can interrupt running tasks <em>while</em> the subclass
     * waits for its own resources to drain (e.g. a scheduler executor).
     *
     * <p>This is a targeted signal: it only sets the volatile fields that {@code isValid()}
     * observes. All remaining cleanup (unregistration, monitor shutdown, latch reset) is
     * still performed by {@link #closeInternal(long)}.
     */
    protected synchronized void signalLeaderInvalid() {
        isLeader = null;
        timestamp = null;
        electionClosed = true;
    }


    /**
     * @see com.github.toolarium.leader.election.ILeaderElector#close()
     */
    @Override
    public void close() throws LeaderElectionException {
        closeInternal(leaderElectionConfiguration.getCloseTimeout().toMillis());
    }


    /**
     * Close the elector state and wait for running leader tasks within the close timeout. Subclasses call this
     * instead of {@code super.close()} when they release their lock afterwards, see
     * {@link #releaseWhenTasksFinished(boolean, String, Runnable)}.
     *
     * @return true if no leader task runs anymore, false if tasks are still running after the close timeout
     *         or close() was called from one of the elector's own tasks
     * @throws LeaderElectionException if cleanup fails
     */
    protected boolean closeAndAwaitTasks() throws LeaderElectionException {
        return closeInternal(leaderElectionConfiguration.getCloseTimeout().toMillis());
    }


    /**
     * Release the lock of a closed elector: at once when no leader task runs anymore, otherwise as soon as the
     * running tasks have finished. Releasing the lock while a task still runs would let another node take the lead
     * while this node still does leader work. A later {@code elect()} starts the new session only once such a delayed
     * release has finished: otherwise the release of the old session could give up a lock the new session holds.
     *
     * @param tasksFinished true if no leader task runs anymore, see {@link #closeAndAwaitTasks()}
     * @param threadName the name of the thread that waits for the running tasks
     * @param release the release of the lock
     */
    protected void releaseWhenTasksFinished(boolean tasksFinished, String threadName, Runnable release) {
        if (tasksFinished) {
            release.run();
            return;
        }

        LOG.info("Leader tasks of [{}] are still running; the lock is released as soon as they have finished.", leaderElectionInformation.getUniqueName());
        synchronized (pendingReleaseMonitor) {
            pendingReleases++;
        }
        Thread releaseThread = new Thread(() -> {
            try {
                // the write lock is only granted once every running task released its read lock
                Lock writeLock = lifecycleLock.writeLock();
                writeLock.lock();
                writeLock.unlock();
                release.run();
            } finally {
                synchronized (pendingReleaseMonitor) {
                    pendingReleases--;
                    pendingReleaseMonitor.notifyAll();
                }
            }
        }, threadName);
        releaseThread.setDaemon(true);
        releaseThread.start();
    }


    /**
     * Wait until the delayed releases of closed sessions have finished, see {@link #releaseWhenTasksFinished(boolean, String, Runnable)}.
     *
     * @param deadlineNanos the deadline ({@link System#nanoTime()}), or {@link #NO_DEADLINE} to wait without a timeout
     * @return true if the releases have finished, false if the deadline passed
     * @throws InterruptedException if the calling thread is interrupted while waiting
     */
    private boolean awaitPendingReleases(long deadlineNanos) throws InterruptedException {
        synchronized (pendingReleaseMonitor) {
            if (pendingReleases > 0 && LOG.isDebugEnabled()) {
                LOG.debug("Election of [{}] waits until the lock of the closed session is released.", leaderElectionInformation.getUniqueName());
            }
            while (pendingReleases > 0) {
                if (deadlineNanos == NO_DEADLINE) {
                    pendingReleaseMonitor.wait();
                } else {
                    long remainingNanos = deadlineNanos - System.nanoTime();
                    if (remainingNanos <= 0) {
                        return false;
                    }
                    TimeUnit.NANOSECONDS.timedWait(pendingReleaseMonitor, remainingNanos);
                }
            }
            return true;
        }
    }


    /**
     * Acquire a lock until the deadline.
     *
     * @param lock the lock
     * @param deadlineNanos the deadline ({@link System#nanoTime()}), or {@link #NO_DEADLINE} to wait without a timeout
     * @return true if the lock was acquired
     * @throws InterruptedException if the calling thread is interrupted while waiting
     */
    private static boolean lockUntil(Lock lock, long deadlineNanos) throws InterruptedException {
        if (deadlineNanos == NO_DEADLINE) {
            lock.lock();
            return true;
        }
        return lock.tryLock(Math.max(0L, deadlineNanos - System.nanoTime()), TimeUnit.NANOSECONDS);
    }


    /**
     * Convert a timeout to nanoseconds, bounded so that adding it to {@link System#nanoTime()} cannot overflow.
     *
     * @param timeout the timeout
     * @return the timeout in nanoseconds, at least 0
     */
    private static long toBoundedNanos(Duration timeout) {
        long maxNanos = Long.MAX_VALUE / 4;
        if (timeout.isNegative()) {
            return 0L;
        }
        if (timeout.compareTo(Duration.ofNanos(maxNanos)) > 0) {
            return maxNanos;
        }
        return timeout.toNanos();
    }


    /**
     * Run a close action serialized with the initialization of the first {@code elect()}, so that close() neither
     * runs while the initialization is half done nor misses resources that the initialization creates concurrently.
     * If the initialization does not finish within the given time, close() returns and the initializing thread runs
     * the close action as soon as the initialization has finished: closing concurrently would leave the resources the
     * initialization creates afterwards (a channel, a lock, the periodic task) with an elector that never reports a
     * result again.
     *
     * @param waitMillis maximum milliseconds to wait for a running initialization
     * @param closeAction the close action
     * @throws LeaderElectionException if the close action fails
     */
    protected void closeSerialized(long waitMillis, CloseAction closeAction) throws LeaderElectionException {
        boolean transitionLocked = false;
        try {
            transitionLocked = tryLockTransition(waitMillis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        if (!transitionLocked) {
            // usually a slow initialization, e.g. a schema creation or a cluster join
            pendingClose.set(closeAction);
            LOG.info("The initialization of [{}] is still running after {} ms; the elector is closed as soon as it has finished.",
                     leaderElectionInformation.getUniqueName(), waitMillis);
            // the initialization may have finished meanwhile without seeing the pending close
            if (transitionLock.tryLock()) {
                unlockTransition();
            }
            return;
        }

        try {
            closeAction.run();
        } finally {
            unlockTransition();
        }
    }


    /**
     * Run a close that was requested while the transition lock was held by another thread. The caller holds the
     * transition lock exactly once.
     */
    private void runPendingClose() {
        CloseAction action = pendingClose.getAndSet(null);
        if (action == null) {
            return;
        }
        try {
            action.run();
        } catch (LeaderElectionException | RuntimeException e) {
            if (LOG.isDebugEnabled()) {
                LOG.debug("Delayed close of [{}] failed: {}", leaderElectionInformation.getUniqueName(), e.toString(), e);
            }
            LOG.info("Delayed close of [{}] failed: {}", leaderElectionInformation.getUniqueName(), e.getMessage());
        }
    }


    /**
     * A close action, see {@link AbstractLeaderElectorImpl#closeSerialized(long, CloseAction)}.
     */
    @FunctionalInterface
    protected interface CloseAction {
        /**
         * Run the close action.
         *
         * @throws LeaderElectionException if the close action fails
         */
        void run() throws LeaderElectionException;
    }


    /**
     * Shared close logic used by {@link #close()} and by subclasses that compute a single
     * close deadline across several steps. Subclasses should call this instead of
     * {@link #close()} when they have already spent part of the close budget waiting for
     * their own resources (e.g. a scheduler executor) so that the total close time does
     * not exceed the configured {@code closeTimeout}.
     *
     * @param writeLockTimeoutMillis maximum milliseconds to wait for the lifecycle write lock
     * @return true if no leader task runs anymore
     * @throws LeaderElectionException if cleanup fails
     */
    protected boolean closeInternal(long writeLockTimeoutMillis) throws LeaderElectionException {
        return closeInternal(Boolean.TRUE.equals(isLeader), writeLockTimeoutMillis);
    }


    /**
     * Shared close logic with an explicit {@code wasLeader} flag. Callers that invalidate
     * the leader state early (via {@link #signalLeaderInvalid()}) before calling this method
     * must capture {@code wasLeader} beforehand and pass it here so that the
     * "Released lead" log is emitted correctly.
     *
     * @param wasLeader              {@code true} if this node held leadership before close
     * @param writeLockTimeoutMillis maximum milliseconds to wait for the lifecycle write lock
     * @return true if no leader task runs anymore, false if tasks are still running after the timeout
     *         or close() was called from one of the elector's own tasks
     * @throws LeaderElectionException if cleanup fails
     */
    protected boolean closeInternal(boolean wasLeader, long writeLockTimeoutMillis) throws LeaderElectionException {
        // Signal any running tasks: setting isLeader=null makes isValid() return false,
        // so the LeaderLeaseMonitor will interrupt them on its next check (within 100 ms).
        // Done under the same intrinsic lock as setLeader() so that a background callback
        // (JGroups receiver, file lock scheduler, ...) cannot write isLeader=true between
        // these field writes and leave a closed elector reporting leadership.
        signalLeaderInvalid();

        // Wait for all running runIfLeader tasks to finish (they hold read locks).
        // The monitor interrupts them within CHECK_PERIOD_MILLIS; the write lock is
        // attempted with a short spin so close() does not block indefinitely when
        // all tasks finish quickly (common case), but proceeds anyway if the timeout
        // elapses (tasks will still be interrupted by the monitor).
        Lock writeLock = lifecycleLock.writeLock();
        boolean locked = false;
        if (lifecycleLock.getReadHoldCount() > 0) {
            // close() called from inside a runIfLeader task: this thread holds a read lock, which can
            // never be upgraded to the write lock, so waiting would only burn the whole close budget.
            if (LOG.isDebugEnabled()) {
                LOG.debug("Close of [{}] was requested by one of its own leader tasks; not waiting for running tasks.", leaderElectionInformation.getUniqueName());
            }
        } else {
            try {
                locked = writeLock.tryLock(Math.max(0L, writeLockTimeoutMillis), TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
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
        return locked;
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

            ReentrantLock taskLock = acquireTaskLock(name);
            if (taskLock == null) {
                if (LOG.isDebugEnabled()) {
                    LOG.debug("Leader task [{}]: task already running, skipping.", name);
                }
                return false;
            }

            DefaultLeaderLease lease = new DefaultLeaderLease(this, leaderElectionInformation, leaderElectionConfiguration, Thread.currentThread(), getLeadershipTerm());
            leaseMonitor.register(lease);

            try {
                if (LOG.isDebugEnabled()) {
                    LOG.debug("Leader task [{}]: starting.", name);
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
                    LOG.debug("Leader task [{}]: interrupted (leadership lost or elector closed).", name);
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
                // Remove the entry when no thread is holding the lock to prevent unbounded map growth with many distinct
                // task names; up to a bound the idle locks are kept and reused.
                if (taskLocks.size() > MAX_IDLE_TASK_LOCKS) {
                    taskLocks.computeIfPresent(name, (k, lock) -> {
                        if (lock.isLocked()) {
                            return lock;
                        }
                        return null;
                    });
                }
                if (LOG.isDebugEnabled()) {
                    LOG.debug("Leader task [{}]: finished.", name);
                }
            }
        } finally {
            readLock.unlock();
        }
    }


    /**
     * Acquire the lock of a named leader task. A finishing task removes its unlocked lock from the map, so a lock
     * fetched from the map may already be removed when it is acquired; a third caller would then create a second
     * lock for the same name and both would run. The lock only counts while it is still the one in the map.
     *
     * @param name the task name
     * @return the acquired lock, or null if the task is already running
     */
    private ReentrantLock acquireTaskLock(String name) {
        while (true) {
            ReentrantLock taskLock = taskLocks.computeIfAbsent(name, k -> new ReentrantLock());
            if (!taskLock.tryLock()) {
                return null;
            }
            if (taskLocks.get(name) == taskLock) {
                // a locked lock is never removed from the map
                return taskLock;
            }
            taskLock.unlock();
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
     * Extend the lease, called by {@link DefaultLeaderLease#tryExtend()}.
     *
     * @return {@code true} if this node is still the leader after the extension attempt
     * @throws LeaderElectionException if the extension attempt fails with an unrecoverable error
     */
    boolean extendLease() throws LeaderElectionException {
        return extendLeaseNow();
    }


    /**
     * Run the work of an extension, e.g. on another thread (DATABASE runs it on a worker thread to avoid JTA-enlisted
     * connections), and wrap its exceptions.
     *
     * @param body the extend work; must not be {@code null}
     * @return the value returned by {@code body}
     * @throws LeaderElectionException if {@code body} throws
     */
    protected final boolean runAsExtension(Callable<Boolean> body) throws LeaderElectionException {
        try {
            return body.call();
        } catch (LeaderElectionException e) {
            throw e;
        } catch (Exception e) {
            throw new LeaderElectionException("Could not extend leader lease: " + e.getMessage(), e);
        }
    }


    /**
     * Set the leader
     *
     * @param isLeader the leader
     * @param inputDescription the description
     */
    protected synchronized void setLeader(final Boolean isLeader, final String inputDescription) {
        // Once close() has invalidated the elector, ignore every late state update from background
        // threads. Refusing only "true" is not enough: a late "false" would turn the cleared (null)
        // state into a definitive result and break the first-result handling after re-election.
        // signalLeaderInvalid() is also synchronized, so electionClosed is visible here
        // without an additional volatile read race.
        if (electionClosed) {
            return;
        }
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
            // I'm the leader; update timestamp on every confirmation so getRemainingLeadTime()
            // reflects the last renewal/extension rather than only the initial acquisition.
            if (this.isLeader == null || !this.isLeader) {
                // Acquisition: increment the term so leases from previous terms cannot become valid again after a lead
                // loss + re-gain.
                leadershipTerm.incrementAndGet();
                LOG.info("Gained lead of [{}]{}", leaderElectionInformation.getUniqueName(), newDescription);
            } else if (System.nanoTime() - confirmedFenceNanos > leaderElectionConfiguration.getRenewDeadline().toNanos()) {
                // The lead was not confirmed within the renew deadline, so it lapsed (isLeader() was false and running tasks
                // were interrupted) without a reported loss: this confirmation starts a new term as well.
                leadershipTerm.incrementAndGet();
                LOG.info("Gained lead of [{}] again after it was not confirmed within the renew deadline{}", leaderElectionInformation.getUniqueName(),
                         newDescription);
            }
            this.timestamp = Instant.now();
            this.leadConfirmedNanos = System.nanoTime();
            // the fence of the strategy: e.g. the start of the database round, or the send time of the answered challenge
            this.confirmedFenceNanos = getLeadConfirmedNanos();
        } else {
            // No longer leader; the LeaderLeaseMonitor will detect isValid()==false on its
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
     * Acquire the transition lock that serializes initialization (first {@code elect()}) against
     * {@code close()}. Subclasses whose {@code close()} tears down resources created by
     * {@link #initializeImplementation()} call this first, so that close() can neither run while
     * initialization is half done nor miss resources that initialization creates concurrently.
     * Lock order: transition lock first, then the lifecycle lock.
     *
     * @param timeoutMillis maximum milliseconds to wait
     * @return true if the lock was acquired; the caller must then call {@link #unlockTransition()}
     * @throws InterruptedException if the calling thread is interrupted while waiting
     */
    protected boolean tryLockTransition(long timeoutMillis) throws InterruptedException {
        return transitionLock.tryLock(Math.max(0L, timeoutMillis), TimeUnit.MILLISECONDS);
    }


    /**
     * Release the transition lock acquired by {@link #tryLockTransition(long)}. A close requested meanwhile by
     * another thread runs first, see {@link #closeSerialized(long, CloseAction)}.
     */
    protected void unlockTransition() {
        boolean again;
        do {
            if (transitionLock.getHoldCount() == 1) {
                runPendingClose();
            }
            transitionLock.unlock();
            // a close requested after the check above would otherwise be lost
            again = pendingClose.get() != null && transitionLock.tryLock();
        } while (again);
    }


    /**
     * Start the election machinery if it has not been started yet (idempotent).
     * Uses the transition lock and the lifecycle write lock so that concurrent calls to
     * {@link #close()} are properly serialized with initialization.
     *
     * @param deadlineNanos the deadline to start the election ({@link System#nanoTime()}), or {@link #NO_DEADLINE} to wait without a timeout
     * @return the election latch of the session this call belongs to, or null if the election could not be started before the deadline
     * @throws LeaderElectionException In case of an initialisation error
     * @throws InterruptedException if the calling thread is interrupted while waiting
     */
    private CountDownLatch startElectionIfNeeded(long deadlineNanos) throws LeaderElectionException, InterruptedException {
        // Fast-path: already initialized; no lock needed (volatile read).
        // Read the latch BEFORE isInitialized: closeInternal() clears isInitialized before it swaps
        // the latch, so if we observe the new latch we also observe isInitialized=false and take the
        // slow path. If close() swaps the latch after our read, it counts down the latch we hold.
        // Either way elect() cannot end up waiting on a latch nobody will ever count down.
        CountDownLatch latch = electionLatch;
        if (isInitialized) {
            return latch;
        }
        if (lifecycleLock.getReadHoldCount() > 0) {
            // Called from inside a runIfLeader task after the elector was closed: initialization needs
            // the write lock, which this thread can never get while it holds the task's read lock.
            throw new LeaderElectionException("elect() must not be called from within a runIfLeader task of a closed elector ["
                                              + leaderElectionInformation.getUniqueName() + "].");
        }
        if (!lockUntil(transitionLock, deadlineNanos)) {
            return null;
        }
        try {
            // before the write lock: a delayed release needs it for a moment
            if (!awaitPendingReleases(deadlineNanos)) {
                return null;
            }
            Lock writeLock = lifecycleLock.writeLock();
            if (!lockUntil(writeLock, deadlineNanos)) {
                return null;
            }
            try {
                // Re-check under lock: another thread may have initialized between the volatile read above and lock acquisition.
                if (!isInitialized) {
                    electionClosed = false;
                    leaseMonitor.start();
                    initializeImplementation();
                    isInitialized = true;
                    // close() unregisters the elector: register it again so the shutdown hook also closes a re-elected elector
                    LeaderElectionScheduler.getInstance().register(this);
                }
                return electionLatch;
            } finally {
                writeLock.unlock();
            }
        } finally {
            // runs a close() requested while this thread initialized
            unlockTransition();
        }
    }
}
