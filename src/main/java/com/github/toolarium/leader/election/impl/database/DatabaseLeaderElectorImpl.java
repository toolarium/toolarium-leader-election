/*
 * DatabaseLeaderElectorImpl.java
 *
 * Copyright by toolarium, all rights reserved.
 */
package com.github.toolarium.leader.election.impl.database;

import com.github.toolarium.leader.election.ILeaderElector;
import com.github.toolarium.leader.election.ILeaderElectorView;
import com.github.toolarium.leader.election.dto.ILeaderElectionConfiguration;
import com.github.toolarium.leader.election.dto.ILeaderElectionInformation;
import com.github.toolarium.leader.election.dto.LeaderElectionInformation;
import com.github.toolarium.leader.election.dto.db.DatabaseLeaderElectionConfiguration;
import com.github.toolarium.leader.election.exception.LeaderElectionException;
import com.github.toolarium.leader.election.impl.AbstractLeaderElectorImpl;
import com.github.toolarium.leader.election.impl.database.dao.LeaderElectionDAO;
import com.github.toolarium.leader.election.impl.database.dao.LeaderElectorRecord;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


/**
 * Implements the {@link ILeaderElector} based on database.
 *
 * <p><b>Clock synchronisation:</b> all {@code lease_ts} writes use the database server clock
 * ({@code SELECT CURRENT_TIMESTAMP}) so expiry decisions are consistent across nodes regardless
 * of local clock skew.  The monotonic renew-deadline guard ({@code lastRenewalNanos}) uses
 * {@code System.nanoTime()} for scheduling purposes only and is not persisted to the database.
 *
 * <p><b>Lifecycle and DataSource:</b> {@link #close()} deletes the leader row so another node
 * can take over immediately.  In frameworks where the DataSource is a managed resource
 * (Spring, Quarkus, Jakarta EE), ensure {@code close()} is called <em>before</em> the
 * DataSource is destroyed — for example via {@code @PreDestroy} or an application shutdown
 * event.  If {@code close()} is only called from the JVM shutdown hook (registered by
 * {@link com.github.toolarium.leader.election.impl.LeaderElectionScheduler}), the DataSource
 * may already be gone at that point; the row deletion will fail silently and the row will
 * remain until it expires by {@code timeout}, delaying failover by up to that duration.
 *
 * @author patrick
 */
public class DatabaseLeaderElectorImpl extends AbstractLeaderElectorImpl<DatabaseLeaderElectionConfiguration> {
    private static final Logger LOG = LoggerFactory.getLogger(DatabaseLeaderElectorImpl.class);
    private static final int CONSECUTIVE_FAILURE_THRESHOLD = 3;
    private static final long MAX_BACKOFF_MILLIS = 60_000L;
    // Refresh the DB clock offset every CLOCK_SYNC_TICKS ticks; between syncs use System.currentTimeMillis() + dbClockOffsetMillis to avoid an extra DB round-trip per tick.
    private static final int CLOCK_SYNC_TICKS = 5;
    // Safety cap on getDatabaseElectorViews(): prevents heap pressure when the table
    // has leaked rows (e.g. crashed nodes that never deleted their row).
    private static final int MAX_ELECTOR_ROWS = 1000;
    // Sentinel for lastRenewalNanos and backoffUntil meaning "not yet set".
    // Long.MIN_VALUE is used rather than 0 because System.nanoTime() has an arbitrary origin
    // and may legally return negative values; using 0 as "not set" silently disables the
    // step-down guards on such platforms.
    private static final long NANOS_NOT_SET = Long.MIN_VALUE;
    // volatile: written by elect() (initialization thread) and read by close() (potentially
    // from another thread, e.g. the JVM shutdown hook registered by LeaderElectionScheduler).
    private volatile ScheduledExecutorService scheduledExecuterService;
    private volatile ScheduledFuture<?> scheduledFuture;
    private LeaderElectionDAO leaderElectionDAO;
    private volatile String currentLeaderRecordId;
    private int consecutiveFailures;  // only accessed by the scheduler thread (reset in initializeImplementation() before scheduler starts)
    private volatile long backoffUntil;
    private volatile long lastRenewalNanos;
    private volatile long dbClockOffsetMillis;
    private volatile long lastClockSyncAt;
    // AtomicBoolean so close() is idempotent under concurrent calls (e.g. app shutdown + JVM hook).
    private final AtomicBoolean closing;
    // Monotonically increasing session counter. Incremented by close() so that a
    // DatabaseElectionHandler from the previous session (still blocked in a DB call) can
    // detect the session change and discard its result without touching the new session's state.
    // AtomicInteger makes the increment in close() atomic without relying on the closing CAS.
    private final AtomicInteger sessionCounter;
    // Set to true once leaderElectionDAO.init() succeeds; guards the DELETE in close() so that
    // electors whose elect() was never called (or whose init failed, e.g. allowDdl=false with a
    // missing table) do not emit a noisy INFO log when close() finds no table to delete from.
    private volatile boolean daoReady;
    // Dedicated lock for dbClockOffsetMillis / lastClockSyncAt so that getDbNow()
    // (which may execute a DB round-trip) does not block threads waiting for
    // synchronized(this), e.g. close() calling signalLeaderInvalid().
    private final Object clockSyncLock = new Object();

    // -----------------------------------------------------------------------
    // Lock acquisition order — MUST be respected to prevent deadlock:
    //   1. lifecycleLock (writeLock or readLock) — AbstractLeaderElectorImpl
    //   2. synchronized(this) — intrinsic lock used by setLeader(),
    //      signalLeaderInvalid(), extendLeaseNow(), and the session-check
    //      blocks in DatabaseElectionHandler / handleOwnLeaderTick()
    //   3. clockSyncLock — guards dbClockOffsetMillis / lastClockSyncAt only;
    //      MUST NOT be acquired while holding synchronized(this) or lifecycleLock
    // -----------------------------------------------------------------------


    /**
     * Constructor for DatabaseLeaderElectorImpl
     *
     * @param leaderElectionInformation the leader election information
     * @param leaderElectionConfiguration the leader election configuration
     */
    public DatabaseLeaderElectorImpl(LeaderElectionInformation leaderElectionInformation, DatabaseLeaderElectionConfiguration leaderElectionConfiguration) {
        super(leaderElectionInformation, leaderElectionConfiguration);
        this.scheduledExecuterService = null;
        this.scheduledFuture = null;
        this.leaderElectionDAO = new LeaderElectionDAO(getLeaderElectionConfiguration());
        this.currentLeaderRecordId = null;
        this.consecutiveFailures = 0;
        this.backoffUntil = NANOS_NOT_SET;
        this.lastRenewalNanos = NANOS_NOT_SET;
        this.dbClockOffsetMillis = 0;
        this.lastClockSyncAt = 0;
        this.closing = new AtomicBoolean(false);
        this.sessionCounter = new AtomicInteger(0);
        this.daoReady = false;
    }


    /**
     * @see com.github.toolarium.leader.election.impl.AbstractLeaderElectorImpl#initializeImplementation()
     */
    @Override
    protected void initializeImplementation() throws LeaderElectionException {
        // Allow reuse after close().
        closing.set(false);
        daoReady = false;              // reset until init() succeeds below
        lastClockSyncAt = 0;           // force a fresh DB clock sync on (re-)initialization
        lastRenewalNanos = NANOS_NOT_SET; // reset nanoTime sentinel so step-down guards are fresh
        backoffUntil = NANOS_NOT_SET;  // reset backoff on re-initialization
        consecutiveFailures = 0;       // reset failure count so log level and backoff start fresh

        LeaderElectionInformation info = getLeaderElectionInformation();
        if ((info.getNamespace() == null || info.getNamespace().isBlank()) && (info.getName() == null || info.getName().isBlank())) {
            LOG.warn("LeaderElectionInformation has no namespace or name set. Falling back to identity [{}] as the election group key. All nodes must use the same identity value to form a valid election group.", info.getIdentity());
        }

        try {
            leaderElectionDAO.init();
            daoReady = true;
        } catch (SQLException e) {
            throw new LeaderElectionException("Could not prepare database for leader election: " + e.getMessage(), e);
        }

        String electionGroupName = getLeaderElectionInformation().getElectionGroupName();
        ThreadFactory daemonFactory = runnable -> {
            Thread t = new Thread(runnable, "leader-election-db-" + electionGroupName);
            t.setDaemon(true);
            return t;
        };
        scheduledExecuterService = Executors.newScheduledThreadPool(1, daemonFactory);
        long retryMillis = getLeaderElectionConfiguration().getRetryPeriod().toMillis();
        // initialDelay=0: the first tick fires in the scheduler's daemon thread immediately
        // after the writeLock from startElectionIfNeeded() is released, so runIfLeader()
        // callers are not blocked for a DB round-trip at startup.
        // elect() blocks on electionLatch.await() until the first tick calls setLeader(),
        // which counts down the latch — the result is visible as soon as the DB responds.
        scheduledFuture = scheduledExecuterService.scheduleAtFixedRate(
                new DatabaseElectionHandler(), 0, retryMillis, TimeUnit.MILLISECONDS);
    }


    /**
     * @see com.github.toolarium.leader.election.impl.AbstractLeaderElectorImpl#extendLeaseNow()
     */
    @Override
    protected boolean extendLeaseNow() throws LeaderElectionException {
        if (!isLeader()) {
            return false;
        }
        String uniqueName = getLeaderElectionInformation().getElectionGroupName();
        String instanceId = getId();
        try {
            long extendStartNanos = System.nanoTime();
            long dbNow = getDbNow();
            LeaderElectorRecord newRecord = new LeaderElectorRecord(uniqueName, instanceId, getLeaderElectionInformation().getUsername(), dbNow);
            int result = leaderElectionDAO.updateLeaderTimestamp(uniqueName, instanceId, newRecord.getId(), newRecord.getTimestamp());
            // Synchronize on the same intrinsic lock as setLeader/signalLeaderInvalid so that
            // a concurrent close() cannot interleave between the field writes and setLeader(true).
            // Re-read via super.isLeader() on the true-branch in case electionClosed caused
            // setLeader(true) to be refused — in that case return false, not true.
            synchronized (this) {
                if (result > 0) {
                    currentLeaderRecordId = newRecord.getId();
                    lastRenewalNanos = extendStartNanos;
                    setLeader(true, null);
                    return super.isLeader();
                } else {
                    currentLeaderRecordId = null;
                    setLeader(false, null);
                    return false;
                }
            }
        } catch (Exception e) {
            throw new LeaderElectionException("Could not extend leader lease: " + e.getMessage(), e);
        }
    }


    /**
     * @see com.github.toolarium.leader.election.ILeaderElector#isLeader()
     *     Overrides the cached boolean with a monotonic-clock expiry check so that a
     *     stuck election thread (e.g. blocked network I/O) cannot keep returning
     *     {@code true} after the renewDeadline has passed.
     */
    @Override
    public boolean isLeader() {
        if (!super.isLeader()) {
            return false;
        }
        long nanos = lastRenewalNanos;
        if (nanos == NANOS_NOT_SET) {
            return true; // elected this tick, nanos not yet set
        }
        return System.nanoTime() - nanos
                <= getLeaderElectionConfiguration().getRenewDeadline().toNanos();
    }


    /**
     * @see com.github.toolarium.leader.election.ILeaderElector#close()
     */
    @Override
    public void close() throws LeaderElectionException {
        // Idempotent: only the first caller proceeds; concurrent or repeated calls return immediately.
        if (!closing.compareAndSet(false, true)) {
            return;
        }

        // Invalidate all DatabaseElectionHandler instances from this session so that any
        // round still blocked in a DB call after awaitTermination() returns will discard its
        // result and not write state to a future session started by elect() after this close().
        sessionCounter.incrementAndGet();

        // Capture wasLeader before signalLeaderInvalid() nulls the field so that
        // closeInternal() can still log "Released lead" correctly.
        boolean wasLeader = super.isLeader();

        // Immediately invalidate the leader state so the LeaderLeaseMonitor (period 100 ms) can
        // interrupt any running runIfLeader task WHILE we wait for the scheduler to drain below.
        // Without this early signal, closeInternal() only sets isLeader=null after awaitTermination()
        // has consumed the whole budget — leaving the task un-interruptible and the row deleted
        // while the task is still running.
        signalLeaderInvalid();

        // Compute a single deadline for the whole close() operation so the scheduler wait
        // and the task-cleanup wait together do not exceed closeTimeout.
        long closeMillis = getLeaderElectionConfiguration().getCloseTimeout().toMillis();
        long closeDeadlineNanos = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(closeMillis);

        // Cancel the scheduler so no new ticks start.
        // Capture into a local to avoid a TOCTOU race on the volatile field between the
        // null-check and the cancel() call (initializeImplementation() could theoretically
        // write a new value if elect() were called concurrently, though close() is already
        // guarded by the closing CAS so this is belt-and-suspenders).
        ScheduledFuture<?> sf = scheduledFuture;
        if (sf != null) {
            try {
                sf.cancel(false);
            } catch (Exception e) {
                // NOP
            }
            scheduledFuture = null;
        }

        ScheduledExecutorService ses = scheduledExecuterService;
        if (ses != null) {
            try {
                ses.shutdown();
                long remainingMs = TimeUnit.NANOSECONDS.toMillis(closeDeadlineNanos - System.nanoTime());
                if (remainingMs > 0) {
                    ses.awaitTermination(remainingMs, TimeUnit.MILLISECONDS);
                }
            } catch (InterruptedException e) {
                // Restore the interrupt flag so the calling thread (e.g. a framework shutdown
                // thread) can observe that it was interrupted and react accordingly.
                Thread.currentThread().interrupt();
            } catch (Exception e) {
                // NOP
            }
            scheduledExecuterService = null;
        }

        // Signal running tasks and wait for them to finish using the remaining budget.
        // This prevents a window where the DB row is already gone (another node can insert)
        // while our task still holds the lease as valid.
        long remainingForSuper = TimeUnit.NANOSECONDS.toMillis(closeDeadlineNanos - System.nanoTime());
        try {
            super.closeInternal(wasLeader, Math.max(0L, remainingForSuper));
        } finally {
            // Attempt to delete the own leader record so another node can take over immediately.
            // The DELETE targets this node's instance column and is a no-op when the row does
            // not exist or belongs to another node. We do NOT guard on currentLeaderRecordId:
            // a tick running concurrently during close() may have renewed the row while also
            // setting currentLeaderRecordId=null (closing branch), leaving the row in the
            // table even though this elector is no longer active.
            // Guard on daoReady: if elect() was never called or init() failed (e.g. allowDdl=false
            // with a missing table), skip the DELETE silently — there is no row to delete.
            //
            // The DELETE runs on a daemon thread joined for at most the remaining closeTimeout
            // budget.  A hanging database cannot cause close() to block beyond closeTimeout;
            // if the DELETE does not finish in time the row expires naturally after `timeout`.
            if (daoReady) {
                final String groupName = getLeaderElectionInformation().getElectionGroupName();
                final String instanceId = getId();
                Thread deleteThread = new Thread(() -> {
                    try {
                        leaderElectionDAO.deleteLeaderByNameAndInstance(groupName, instanceId);
                    } catch (Exception e) {
                        if (LOG.isDebugEnabled()) {
                            LOG.debug("Error detail:", e);
                        }
                        LOG.info("Could not delete leader record on close for [{}]: {}, the row will expire after the configured timeout. "
                                + "To avoid this delay call close() before the DataSource is destroyed (e.g. @PreDestroy).",
                                groupName, e.getMessage());
                    }
                }, "leader-election-db-close-" + getLeaderElectionInformation().getName());
                deleteThread.setDaemon(true);
                deleteThread.start();
                long remainingForDelete = TimeUnit.NANOSECONDS.toMillis(closeDeadlineNanos - System.nanoTime());
                if (remainingForDelete > 0) {
                    try {
                        deleteThread.join(remainingForDelete);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                }
                if (deleteThread.isAlive()) {
                    LOG.info("close() timed out before the leader record for [{}] could be deleted; "
                            + "the row will expire after the configured timeout. "
                            + "To avoid this delay call close() before the DataSource is destroyed (e.g. @PreDestroy).",
                            groupName);
                }
            }
            currentLeaderRecordId = null;
            // Reset daoReady so that the flag accurately reflects the closed state between
            // this close() and a future elect() call.  initializeImplementation() sets it
            // back to true once init() succeeds, so re-election is unaffected.
            daoReady = false;

            leaderElectionDAO.close();
        }
    }


    /**
     * Query the database for all currently stored leader records and return them as read-only views.
     * Each row in the table represents one election group's current leader — including leaders held
     * by other nodes in the cluster. Local leaders (this JVM) are included as well; the caller
     * (LeaderElectionScheduler) deduplicates by id.
     *
     * <p><b>Data exposure:</b> each view reflects raw database column values, including the OS
     * username stored in the {@code username} column by each cluster node. Callers that log or
     * display view fields should be aware that this data originates from the database and may
     * include information contributed by other nodes.
     *
     * <p>Results are capped at {@value #MAX_ELECTOR_ROWS} rows; a WARN is logged if the table
     * exceeds that limit.
     *
     * @return unmodifiable collection of views; never {@code null}
     */
    public Collection<ILeaderElectorView> getDatabaseElectorViews() {
        try {
            List<LeaderElectorRecord> records = leaderElectionDAO.selectAll();
            if (records.size() > MAX_ELECTOR_ROWS) {
                LOG.warn("getDatabaseElectorViews(): result truncated to {} rows (table contains {}). "
                        + "Check for leaked rows in the leader-election table.", MAX_ELECTOR_ROWS, records.size());
                records = records.subList(0, MAX_ELECTOR_ROWS);
            }
            List<ILeaderElectorView> views = new ArrayList<>(records.size());
            for (LeaderElectorRecord record : records) {
                views.add(new DatabaseRecordView(record));
            }
            return List.copyOf(views);
        } catch (Exception e) {
            LOG.warn("Could not query stored leader records: {}", e.getMessage());
            return Collections.emptyList();
        }
    }


    /**
     * Force-release the leadership of a remote node identified by its stable instance id.
     *
     * <p>Sets a {@code force_release_deadline} in the database row equal to
     * {@code dbNow + renewDeadline}. This prevents the remote holder from renewing its lease
     * and prevents any other node from acquiring leadership until the deadline elapses.
     * Combined with the local {@code isLeader()} monotonic-expiry check (Fix 1), this
     * eliminates the split-brain window that would otherwise occur with a plain row delete:
     * the current holder's lease expires at most {@code renewDeadline} after the last renewal,
     * and no other node may acquire before the same point.
     *
     * <p><b>Overlap bound:</b> the remote node steps down on its next scheduler tick (up to
     * {@code retryPeriod} after the deadline is written). A new leader can only acquire after
     * the deadline elapses (= {@code renewDeadline} from now). Since {@code renewDeadline >= retryPeriod},
     * the new leader acquires only after the old holder has already stepped down — no split-brain.
     *
     * <p><b>Access control:</b> this method is privileged — it can disrupt leadership for any
     * remote node in the cluster. The framework provides no built-in authorisation check; the
     * caller is responsible for ensuring that only authorised principals (e.g. administrators)
     * can invoke it.
     *
     * @param instanceId the stable instance id ({@link ILeaderElectorView#getId()}) of the remote leader
     * @return {@code true} if a matching record was found and the deadline was set
     */
    public boolean forceReleaseRemote(String instanceId) {
        if (instanceId == null || instanceId.isBlank()) {
            LOG.warn("forceReleaseRemote: instanceId must not be null or blank.");
            return false;
        }
        try {
            long renewDeadlineMillis = getLeaderElectionConfiguration().getRenewDeadline().toMillis();
            long deadline;
            try {
                deadline = leaderElectionDAO.getDatabaseCurrentTimeMillis() + renewDeadlineMillis;
            } catch (Exception e) {
                if (LOG.isDebugEnabled()) {
                    LOG.debug("Could not fetch DB clock for force-release [{}], using local clock: {}", instanceId, e.getMessage());
                }
                deadline = System.currentTimeMillis() + renewDeadlineMillis;
            }
            int updated = leaderElectionDAO.setForceReleaseDeadline(instanceId, deadline);
            if (updated > 0) {
                LOG.info("Force-release deadline set for remote leader instance [{}] (deadline in {}ms).", instanceId, renewDeadlineMillis);
                return true;
            }
        } catch (Exception e) {
            LOG.warn("Could not set force-release deadline for remote leader [{}]: {}", instanceId, e.getMessage());
        }
        return false;
    }


    /**
     * Pre-tick renew-deadline guard: called BEFORE any DB operation so a hanging or
     * unavailable database cannot keep this node as leader indefinitely.
     *
     * <p>Uses {@code super.isLeader()} (the stored flag from {@code AbstractLeaderElectorImpl})
     * rather than the overridden {@link #isLeader()} so the check fires even when the
     * nanoTime-based expiry already returned {@code false} on the previous call.
     *
     * <p>Uses {@code System.nanoTime()} instead of {@code System.currentTimeMillis()} so that
     * a wall-clock jump forward (NTP correction, VM resume after suspend) does not cause a
     * healthy leader to step down prematurely. The nanoTime reference is consistent with the
     * overridden {@link #isLeader()} check that uses the same clock.
     *
     * <p>The DB row is intentionally <em>not</em> deleted here. After step-down,
     * {@code handleOwnLeaderTick()} will find the own row and re-acquire it once
     * {@code lease_ts + timeout} has passed (Issue 1 fix), giving any in-flight
     * {@code runIfLeader} task the full {@code (timeout - renewDeadline)} window to stop
     * before another node can acquire. Deleting here would be inconsistent with the
     * keep-and-re-acquire path and also subject to a wall-clock race.
     *
     * @param uniqueName the election group name
     * @param instanceId this node's instance identifier (unused, kept for symmetry)
     * @return {@code true} if the caller should return immediately (leadership was relinquished)
     */
    private boolean checkPreTickRenewDeadline(String uniqueName, String instanceId) {
        if (super.isLeader() && lastRenewalNanos != NANOS_NOT_SET) {
            if (System.nanoTime() - lastRenewalNanos > getLeaderElectionConfiguration().getRenewDeadline().toNanos()) {
                LOG.info("Renew deadline exceeded (pre-tick) for [{}], stepping down as leader.", uniqueName);
                synchronized (this) {
                    currentLeaderRecordId = null;
                    setLeader(false, null);
                }
                // Row stays in the DB — handleOwnLeaderTick() will re-acquire it once it
                // has expired, keeping the behaviour consistent with the non-pre-tick path.
                return true;
            }
        }
        return false;
    }


    /**
     * Handles the election tick when this instance currently holds the DB leader row.
     * Decides whether to keep, renew, or relinquish leadership based on the force-release
     * deadline and the nanoTime-based {@link #isLeader()} check.
     *
     * @param mySession       session counter value captured when the handler was created
     * @param roundStartNanos nanoTime captured at the very start of the tick (before getDbNow)
     * @param dbNow           current DB-clock epoch milliseconds
     * @param uniqueName      the election group name
     * @param instanceId      this node's instance identifier
     * @param currentLeader   the current DB row for this election group (owned by this instance)
     * @return {@code true} if the caller should return immediately (stale session or closing)
     * @throws Exception on DB access errors
     */
    private boolean handleOwnLeaderTick(int mySession, long roundStartNanos, long dbNow, String uniqueName, String instanceId, LeaderElectorRecord currentLeader) throws Exception {
        long frd = currentLeader.getForceReleaseDeadline();
        if (!isLeader()) {
            // We hold the DB row but have already stepped down locally (renewDeadline exceeded or
            // force-release processed on a previous tick). Ensure the stored flag is false so the
            // term is incremented on the next acquisition.
            synchronized (this) {
                setLeader(false, null);
            }
            if (frd != 0 && frd > dbNow) {
                // Force-release deadline still in the future — keep the row as a guard so no
                // other node can acquire before the deadline elapses. Nothing to do this tick.
            } else {
                // Check whether the lease has already expired. If so, re-acquire it immediately
                // using the same conditional UPDATE as stealLeadership so no other node wins the
                // race. This fixes the case where the DB was unreachable when the pre-tick guard
                // stepped us down: the delete failed, the row stayed, and without this path we
                // would loop here forever with isLeader()=false and no one taking over.
                long timeout = getLeaderElectionConfiguration().getTimeout().toMillis();
                long expiryThreshold = dbNow - timeout;
                boolean leaseExpired = currentLeader.getTimestamp() < expiryThreshold;
                boolean forceReleaseReady = frd != 0 && frd <= dbNow;
                if (leaseExpired || forceReleaseReady) {
                    // Best-effort pre-allocation guard: skip the LeaderElectorRecord (and its UUID)
                    // allocation when closing or session is already stale. The authoritative check
                    // remains inside the synchronized block after the DB call.
                    if (closing.get() || mySession != sessionCounter.get()) {
                        return true;
                    }
                    LeaderElectorRecord newRecord = new LeaderElectorRecord(uniqueName, instanceId, getLeaderElectionInformation().getUsername(), dbNow);
                    int result = leaderElectionDAO.stealLeadership(uniqueName, currentLeader.getId(), expiryThreshold, dbNow, newRecord);
                    // Mid-round session check and state update run under the same intrinsic lock as
                    // signalLeaderInvalid() so that close()+elect() cannot interleave between the check
                    // and the setLeader(true)/field writes (Issue 3 fix).
                    synchronized (this) {
                        if (mySession != sessionCounter.get() || closing.get()) {
                            return true;
                        }
                        if (result > 0) {
                            currentLeaderRecordId = newRecord.getId();
                            lastRenewalNanos = roundStartNanos;
                            setLeader(true, null);
                        } else {
                            currentLeaderRecordId = null;
                        }
                    }
                } else {
                    // Lease still within timeout window — keep the row as a guard until it ages
                    // out, giving any in-flight runIfLeader task the (timeout - renewDeadline)
                    // window to notice and stop before another node can acquire.
                    synchronized (this) {
                        currentLeaderRecordId = null;
                    }
                }
            }
        } else if (frd != 0) {
            // Admin requested force-release — step down without renewing.
            // Do NOT delete the row: the deadline guards new acquisitions until frd elapses,
            // preventing a split-brain between our (soon-to-expire) lease and the new leader.
            LOG.info("Force-release deadline detected for [{}], stepping down as leader.", uniqueName);
            synchronized (this) {
                currentLeaderRecordId = null;
                setLeader(false, null);
            }
        } else {
            // We are the leader — atomically renew the lease using the DB clock.
            // The UPDATE condition checks force_release_deadline IS NULL so a concurrent
            // forceRelease that set the deadline between our SELECT and UPDATE cannot be bypassed.
            LeaderElectorRecord newRecord = new LeaderElectorRecord(uniqueName, instanceId, getLeaderElectionInformation().getUsername(), dbNow);
            int result = leaderElectionDAO.updateLeaderTimestamp(uniqueName, instanceId, newRecord.getId(), newRecord.getTimestamp());
            // Mid-round session check and state update run under the same intrinsic lock as
            // signalLeaderInvalid() so that close()+elect() cannot interleave between the check
            // and the setLeader(true)/field writes (Issue 3 fix).
            synchronized (this) {
                if (mySession != sessionCounter.get() || closing.get()) {
                    return true;
                }
                if (result > 0) {
                    currentLeaderRecordId = newRecord.getId();
                    lastRenewalNanos = roundStartNanos;
                    setLeader(true, null);
                } else {
                    currentLeaderRecordId = null;
                    setLeader(false, null);
                }
            }
        }
        return false;
    }


    /**
     * Handles a {@link Throwable} thrown during an election tick: logs, applies the
     * catch-block step-down check using {@code super.isLeader()} (stored flag), ensures
     * {@code elect()} can unblock on first-round DB failure, and schedules back-off.
     *
     * @param e          the throwable
     * @param uniqueName the election group name
     */
    private void handleTickError(Throwable e, String uniqueName) {
        consecutiveFailures++;
        if (e instanceof Error) {
            // Log at ERROR so operators notice the JVM-level problem.  The scheduler is kept
            // alive: re-throwing would cancel all future executions of scheduleAtFixedRate.
            LOG.error("Fatal error in election tick for [{}]: {}", uniqueName, e.getMessage());
            if (LOG.isDebugEnabled()) {
                LOG.debug("Error detail:", e);
            }
        } else if (consecutiveFailures >= CONSECUTIVE_FAILURE_THRESHOLD) {
            if (LOG.isDebugEnabled()) {
                LOG.debug("Error detail:", e);
            }
            LOG.error("Repeated failure ({}) verifying database cluster [{}]: {}", consecutiveFailures, uniqueName, e.getMessage());
        } else {
            LOG.warn("Error occurred while verifying database cluster [{}]: {}", uniqueName, e.getMessage());
            if (LOG.isDebugEnabled()) {
                LOG.debug("Error detail:", e);
            }
        }

        // If we hold leadership but haven't successfully renewed within the renew deadline,
        // voluntarily step down so another node can take over before the full timeout elapses.
        // Uses super.isLeader() (stored flag) — not the overridden isLeader() — for the same
        // reason as checkPreTickRenewDeadline: the nanoTime check may already be false.
        // Uses nanoTime (same as the isLeader() override and checkPreTickRenewDeadline) so that
        // a wall-clock jump cannot trigger a spurious step-down.
        if (super.isLeader() && lastRenewalNanos != NANOS_NOT_SET
                && System.nanoTime() - lastRenewalNanos > getLeaderElectionConfiguration().getRenewDeadline().toNanos()) {
            LOG.info("Renew deadline exceeded for [{}], stepping down as leader.", uniqueName);
            synchronized (this) {
                currentLeaderRecordId = null;
                setLeader(false, null);
            }
        }

        // Ensure elect() can return even when the very first round fails with a DB error.
        if (!hasElectionResult()) {
            setLeader(false, null);
        }

        // Cap the backoff so it never pushes the next attempt past the renew deadline.
        // Both candidate and cap use System.nanoTime() so a wall-clock jump backward
        // (NTP correction, VM resume) cannot extend the backoff window unexpectedly.
        long retryPeriodNanos = getLeaderElectionConfiguration().getRetryPeriod().toNanos();
        long exp = 1L << Math.min(consecutiveFailures - 1, 5);
        long backoffNanos = Math.min(retryPeriodNanos * exp, TimeUnit.MILLISECONDS.toNanos(MAX_BACKOFF_MILLIS));
        long candidate = System.nanoTime() + backoffNanos;
        long renewDeadlineCap;
        if (lastRenewalNanos != NANOS_NOT_SET) {
            renewDeadlineCap = lastRenewalNanos + getLeaderElectionConfiguration().getRenewDeadline().toNanos();
        } else {
            renewDeadlineCap = Long.MAX_VALUE;
        }
        backoffUntil = Math.min(candidate, renewDeadlineCap);
    }


    /**
     * Returns the database server's current time as epoch milliseconds.
     * The offset between the DB clock and the local clock is cached and refreshed every
     * {@value #CLOCK_SYNC_TICKS} ticks ({@code retryPeriod * CLOCK_SYNC_TICKS}).
     * Between refreshes, {@code System.currentTimeMillis() + dbClockOffsetMillis} is used
     * so no extra DB connection is needed on most ticks.
     *
     * <p>If the DB clock refresh fails, the stale offset is kept (or the local clock is used
     * on first call) so the tick can still proceed — if the DB is truly unreachable the
     * subsequent {@code selectLeaderByName()} call will fail and trigger the normal backoff.
     *
     * <p><b>Staleness bound:</b> if the cached offset has not been refreshed for more than
     * {@code 2 × retryPeriod × CLOCK_SYNC_TICKS}, it is reset to zero (pure local clock).
     * This prevents a prolonged DB outage from letting an arbitrarily old offset silently
     * skew lease timestamps on recovery.
     *
     * @return epoch milliseconds anchored to the DB server clock (best-effort)
     */
    private long getDbNow() {
        long retryMillis = getLeaderElectionConfiguration().getRetryPeriod().toMillis();
        long now = System.currentTimeMillis();
        // Saturating multiply: guard against overflow for extreme retryPeriod values.
        long refreshThresholdMs;
        if (retryMillis <= Long.MAX_VALUE / CLOCK_SYNC_TICKS) {
            refreshThresholdMs = retryMillis * CLOCK_SYNC_TICKS;
        } else {
            refreshThresholdMs = Long.MAX_VALUE;
        }
        long stalenessThresholdMs;
        if (retryMillis <= Long.MAX_VALUE / (CLOCK_SYNC_TICKS * 2L)) {
            stalenessThresholdMs = retryMillis * CLOCK_SYNC_TICKS * 2L;
        } else {
            stalenessThresholdMs = Long.MAX_VALUE;
        }

        // Read the sync timestamp under the lock, then perform the DB call outside it so
        // that a blocked DB round-trip never prevents close()/signalLeaderInvalid() from
        // acquiring synchronized(this).
        boolean needsRefresh;
        synchronized (clockSyncLock) {
            needsRefresh = lastClockSyncAt == 0 || now - lastClockSyncAt > refreshThresholdMs;
        }
        if (needsRefresh) {
            try {
                long dbNow = leaderElectionDAO.getDatabaseCurrentTimeMillis();
                // Write fields back under the lock; use the pre-call `now` so that
                // lastClockSyncAt reflects when the refresh was initiated (prevents
                // concurrent callers from both issuing the DB call).
                synchronized (clockSyncLock) {
                    dbClockOffsetMillis = dbNow - now;
                    lastClockSyncAt = now;
                }
                return dbNow;
            } catch (Exception e) {
                synchronized (clockSyncLock) {
                    if (lastClockSyncAt > 0) {
                        if (LOG.isDebugEnabled()) {
                            LOG.debug("Could not refresh DB clock offset for [{}], using stale offset: {}",
                                    getLeaderElectionInformation().getElectionGroupName(), e.getMessage());
                        }
                    } else {
                        LOG.info("Could not fetch initial DB clock for [{}], using local clock: {}",
                                getLeaderElectionInformation().getElectionGroupName(), e.getMessage());
                    }
                }
            }
        }

        synchronized (clockSyncLock) {
            // Staleness bound: if the offset has not been refreshed within twice the normal refresh
            // window, reset it to zero (fall back to pure local clock). A stale offset that has
            // drifted during a prolonged DB outage would skew all lease timestamps on recovery.
            // Lease writes during the outage fail at the DB call regardless, so local time is safe.
            if (lastClockSyncAt > 0 && now - lastClockSyncAt > stalenessThresholdMs) {
                if (LOG.isDebugEnabled()) {
                    LOG.debug("DB clock offset for [{}] is stale ({}ms old), resetting to zero (local clock).",
                            getLeaderElectionInformation().getElectionGroupName(), now - lastClockSyncAt);
                }
                dbClockOffsetMillis = 0;
            }
            return now + dbClockOffsetMillis;
        }
    }


    /**
     * The database leader election handler
     *
     * @author patrick
     */
    private final class DatabaseElectionHandler implements Runnable {
        // Capture the session at construction so that a handler from a previous session
        // (still blocked in a DB call when close() + elect() cycle runs) can detect the
        // session change and discard its result.
        private final int mySession = sessionCounter.get();


        /**
         * @see java.lang.Runnable#run()
         */
        public void run() {
            // Discard ticks that belong to a closed session: close() increments sessionCounter.
            if (mySession != sessionCounter.get()) {
                return;
            }
            // Do not execute any more ticks after close() has been called.
            if (closing.get()) {
                return;
            }
            // Exponential back-off: skip this tick if we are still within the back-off window.
            // Uses nanoTime so a wall-clock step backwards does not extend the backoff.
            if (backoffUntil != NANOS_NOT_SET && System.nanoTime() < backoffUntil) {
                return;
            }

            String uniqueName = getLeaderElectionInformation().getElectionGroupName();
            String instanceId = getId();

            // Pre-tick renew-deadline guard: checked BEFORE any DB call via checkPreTickRenewDeadline().
            // super.isLeader() (stored flag) is used there — not the overridden isLeader() — so the
            // guard fires even when the nanoTime expiry already returned false on the previous call.
            if (checkPreTickRenewDeadline(uniqueName, instanceId)) {
                return;
            }

            // Track whether THIS round wrote a new row (insert or steal from another instance).
            // Used by the finally block: only rows created by THIS round are deleted there.
            // Rows that existed before close() ran (renewals) are left for close() to remove
            // AFTER closeInternal() finishes waiting for running runIfLeader tasks — preventing
            // a split-brain window where another node acquires before this node's task stops.
            boolean rowWrittenThisRound = false;
            try {
                // Capture local nanoTime at the very start of the round so that lastRenewalNanos is never set to a time
                // AFTER the DB write; using nanoTime() after the DB call would extend the local lease by the round-trip
                // duration, potentially creating a split-brain window.
                long roundStartNanos = System.nanoTime();
                // Obtain a DB-clock-anchored timestamp (cached offset, refreshed every CLOCK_SYNC_TICKS ticks) so all
                // writes and expiry checks use the same reference point without an extra DB round-trip on most ticks.
                long dbNow = getDbNow();
                LeaderElectorRecord currentLeader = leaderElectionDAO.selectLeaderByName(uniqueName);
                // Mid-round session/closing guard: a round blocked in selectLeaderByName during a
                // close()+elect() cycle must not contaminate the new session's state.
                if (mySession != sessionCounter.get() || closing.get()) {
                    return;
                }

                if (currentLeader == null) {
                    // No leader exists — try to become one.
                    LeaderElectorRecord newRecord = new LeaderElectorRecord(uniqueName, instanceId, getLeaderElectionInformation().getUsername(), dbNow);
                    int result = leaderElectionDAO.insertLeader(newRecord);
                    if (result > 0) {
                        rowWrittenThisRound = true;
                    }
                    // Session check and state update are atomic under the same intrinsic lock as
                    // signalLeaderInvalid() so that close()+elect() cannot interleave between the
                    // check and the setLeader(true)/field writes (Issue 3 fix).
                    synchronized (DatabaseLeaderElectorImpl.this) {
                        if (mySession != sessionCounter.get() || closing.get()) {
                            return;
                        }
                        if (result > 0) {
                            currentLeaderRecordId = newRecord.getId();
                            lastRenewalNanos = roundStartNanos;
                            setLeader(true, null);
                        } else {
                            setLeader(false, null);
                        }
                    }
                } else if (instanceId.equals(currentLeader.getInstance())) {
                    if (handleOwnLeaderTick(mySession, roundStartNanos, dbNow, uniqueName, instanceId, currentLeader)) {
                        return;
                    }
                } else {
                    // Another instance holds leadership — check expiry and force-release deadline.
                    long timeout = getLeaderElectionConfiguration().getTimeout().toMillis();
                    long expiryThreshold = dbNow - timeout;
                    boolean leaseExpired = currentLeader.getTimestamp() < expiryThreshold;
                    long frd = currentLeader.getForceReleaseDeadline();
                    boolean forceReleaseReady = frd != 0 && frd <= dbNow;

                    if (leaseExpired || forceReleaseReady) {
                        // Lease expired or force-release deadline elapsed — atomically steal leadership.
                        // Best-effort pre-allocation guard: skip LeaderElectorRecord allocation when
                        // closing or session already stale. Authoritative check is inside synchronized.
                        if (closing.get() || mySession != sessionCounter.get()) {
                            return;
                        }
                        LeaderElectorRecord newRecord = new LeaderElectorRecord(uniqueName, instanceId, getLeaderElectionInformation().getUsername(), dbNow);
                        int result = leaderElectionDAO.stealLeadership(uniqueName, currentLeader.getId(), expiryThreshold, dbNow, newRecord);
                        if (result > 0) {
                            rowWrittenThisRound = true;
                        }
                        // Session check and state update are atomic under the same intrinsic lock as
                        // signalLeaderInvalid() so that close()+elect() cannot interleave between the
                        // check and the setLeader(true)/field writes (Issue 3 fix).
                        synchronized (DatabaseLeaderElectorImpl.this) {
                            if (mySession != sessionCounter.get() || closing.get()) {
                                return;
                            }
                            if (result > 0) {
                                currentLeaderRecordId = newRecord.getId();
                                lastRenewalNanos = roundStartNanos;
                                setLeader(true, null);
                            } else {
                                setLeader(false, null);
                            }
                        }
                    } else {
                        synchronized (DatabaseLeaderElectorImpl.this) {
                            setLeader(false, null);
                        }
                    }
                }
                consecutiveFailures = 0;
                backoffUntil = NANOS_NOT_SET;
            } catch (Throwable e) {
                // handleTickError() logs, steps down if renewDeadline is exceeded, and schedules
                // backoff.  We intentionally do NOT re-throw Errors: with scheduleAtFixedRate a
                // thrown exception cancels ALL future executions, so re-throwing a transient Error
                // (e.g. OOM during a load peak, StackOverflowError in a JDBC driver) would
                // permanently stop this elector without any further recovery attempt.  The ERROR
                // log from handleTickError() is the operator's signal to investigate.
                handleTickError(e, uniqueName);
            } finally {
                // If close() is in progress OR this round belongs to a stale session (close()+elect()
                // cycled while blocked in a DB call), AND this round wrote a new row (insert or steal
                // from another instance), delete it so other nodes are not delayed by the full timeout.
                // Renewal rows (updateLeaderTimestamp) are excluded: they existed before close()
                // ran and are removed by close() itself only after closeInternal() has waited for
                // all running runIfLeader tasks to finish, preventing the split-brain overlap.
                if ((closing.get() || mySession != sessionCounter.get()) && rowWrittenThisRound) {
                    try {
                        leaderElectionDAO.deleteLeaderByNameAndInstance(uniqueName, instanceId);
                    } catch (Exception ex) {
                        // best-effort; the row will expire after timeout
                    }
                }
            }
        }
    }


    /**
     * Read-only {@link ILeaderElectorView} backed by a single database row.
     * Represents the current leader for one election group as stored in the database,
     * which may be a leader on a remote node in the cluster.
     *
     * @author patrick
     */
    private final class DatabaseRecordView implements ILeaderElectorView {
        private final LeaderElectorRecord record;
        // Cached at construction so repeated calls to getLeaderElectionInformation() do not
        // allocate a new LeaderElectionInformation object each time (e.g. in admin monitor loops).
        private final LeaderElectionInformation cachedInfo;


        /**
         * Constructor
         *
         * @param record the database leader record
         */
        DatabaseRecordView(LeaderElectorRecord record) {
            this.record = record;
            LeaderElectionInformation base = DatabaseLeaderElectorImpl.this.getLeaderElectionInformation();
            this.cachedInfo = new LeaderElectionInformation(base.getNamespace(), base.getName(), record.getInstance());
        }


        /**
         * @see com.github.toolarium.leader.election.ILeaderElectorView#getId()
         */
        @Override
        public String getId() {
            // The instance column stores the stable elector UUID (== AbstractLeaderElectorImpl.getId()
            // on the owning node), making this id consistent for both local and remote views.
            return record.getInstance();
        }


        /**
         * @see com.github.toolarium.leader.election.ILeaderElectorView#getLeaderElectionInformation()
         */
        @Override
        public ILeaderElectionInformation getLeaderElectionInformation() {
            return cachedInfo;
        }


        /**
         * @see com.github.toolarium.leader.election.ILeaderElectorView#getLeaderElectionConfiguration()
         */
        @Override
        public ILeaderElectionConfiguration getLeaderElectionConfiguration() {
            return DatabaseLeaderElectorImpl.this.getLeaderElectionConfiguration();
        }


        /**
         * @see com.github.toolarium.leader.election.ILeaderElectorView#getLeaderElectorTimestamp()
         */
        @Override
        public Instant getLeaderElectorTimestamp() {
            return Instant.ofEpochMilli(record.getTimestamp());
        }
    }
}
