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
import com.github.toolarium.leader.election.impl.LeaderElectionExecutors;
import com.github.toolarium.leader.election.impl.database.dao.LeaderElectionDAO;
import com.github.toolarium.leader.election.impl.database.dao.LeaderElectorRecord;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import javax.sql.DataSource;
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
 * DataSource is destroyed, for example via {@code @PreDestroy} or an application shutdown
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
    // Safety cap on getDatabaseElectorViews(): prevents heap pressure when the table has leaked rows (e.g. crashed nodes that never deleted their row).
    private static final int MAX_ELECTOR_ROWS = 1000;
    // Sentinel for lastRenewalNanos and backoffUntil meaning "not yet set". Long.MIN_VALUE is used rather than 0 because System.nanoTime() has an arbitrary origin
    // and may legally return negative values; using 0 as "not set" silently disables the step-down guards on such platforms.
    private static final long NANOS_NOT_SET = Long.MIN_VALUE;
    // Maximum time to wait for the schema-init thread (CREATE TABLE, migrations) before failing.
    private static final long SCHEMA_INIT_TIMEOUT_MILLIS = 30_000L;
    // The tables already validated per data source, so that electors sharing a table and re-elections do not check the
    // schema again. Weak keys: a data source that is no longer used does not stay referenced.
    private static final Map<DataSource, Set<String>> VALIDATED_SCHEMAS = Collections.synchronizedMap(new WeakHashMap<>());
    private static final String AUTO_DETECTED_SCHEMA = "<auto-detected>";
    // The periodic election rounds on the threads shared by all electors (LeaderElectionExecutors).
    // volatile: written by elect() (initialization thread) and read by close() (potentially from another thread, e.g. the JVM shutdown hook registered by LeaderElectionScheduler).
    private volatile LeaderElectionExecutors.PeriodicTask electionTask;
    private LeaderElectionDAO leaderElectionDAO;
    private volatile String currentLeaderRecordId;
    // the record id of the last lease row this elector wrote successfully; close() deletes the row only while it still holds
    // this id, so a late delete cannot remove a row the elector wrote after it was elected again
    private volatile String lastWrittenRecordId;
    // Updated under synchronized(this) together with a session check, so a tick of a previous session that is still running cannot change
    // the counter of the current session. volatile: reset in initializeImplementation() without the lock.
    private volatile int consecutiveFailures;
    // true once the current run of failures was warned about: further failures are logged at debug level until a round succeeds
    private volatile boolean failureReported;
    private volatile long backoffUntil;
    private volatile long lastRenewalNanos;
    private volatile long dbClockOffsetMillis;
    // lastClockSyncAt: concurrency guard, set optimistically before the DB call, reset to 0 on
    // failure so the next tick retries. Does NOT represent a successful sync (Finding 4).
    private volatile long lastClockSyncAt;
    // lastSuccessfulClockSyncAt: records the wall-clock time of the last successful DB clock sync.
    // Used for the staleness bound and to distinguish "never synced" from "failed retry" in logs.
    private volatile long lastSuccessfulClockSyncAt;
    // AtomicBoolean so close() is idempotent under concurrent calls (e.g. app shutdown + JVM hook).
    private final AtomicBoolean closing;
    // Monotonically increasing session counter. Incremented by close() so that a DatabaseElectionHandler from the previous session (still blocked in a DB call) can
    // detect the session change and discard its result without touching the new session's state. AtomicInteger makes the increment in close() atomic without relying on the closing CAS.
    private final AtomicInteger sessionCounter;
    // Set to true once leaderElectionDAO.init() succeeds; guards the DELETE in close() so that electors whose elect() was never called (or whose init failed, e.g. allowDdl=false with a
    // missing table) do not emit a noisy INFO log when close() finds no table to delete from.
    private volatile boolean daoReady;
    // Set to true once the schema has been initialised (by the factory at build time or by the
    // first initializeImplementation() call). Reset by close() so that re-election after close
    // re-validates and re-migrates the schema if needed.
    private final AtomicBoolean schemaReady = new AtomicBoolean(false);
    // Dedicated lock for dbClockOffsetMillis / lastClockSyncAt so that getDbNow() (which may execute a DB round-trip) does not block threads waiting for
    // synchronized(this), e.g. close() calling signalLeaderInvalid().
    private final Object clockSyncLock = new Object();
    // Guards initializeSchema() so only one caller runs the DDL; concurrent callers wait for the result.
    private final Object schemaInitLock = new Object();
    // At most one lease extension per elector runs at a time; concurrent tryExtend() calls wait for the same result, so a slow
    // data source does not pile up worker threads.
    private final Object extensionLock = new Object();
    private Future<Boolean> pendingExtension;
    private int pendingExtensionSession;

    // -----------------------------------------------------------------------
    // Lock acquisition order: MUST be respected to prevent deadlock:
    //   0. transitionLock: AbstractLeaderElectorImpl; held by elect() initialization and by close()
    //   1. lifecycleLock (writeLock or readLock): AbstractLeaderElectorImpl
    //   2. synchronized(this): intrinsic lock used by setLeader(), signalLeaderInvalid(), doDbExtendLease(), and the session-check blocks in DatabaseElectionHandler / renewOwnLease() / handleOwnLeaderTick()
    //   3. clockSyncLock: guards dbClockOffsetMillis / lastClockSyncAt only; MUST NOT be acquired while holding synchronized(this) or lifecycleLock
    // -----------------------------------------------------------------------


    /**
     * Constructor for DatabaseLeaderElectorImpl
     *
     * @param leaderElectionInformation the leader election information
     * @param leaderElectionConfiguration the leader election configuration
     */
    public DatabaseLeaderElectorImpl(LeaderElectionInformation leaderElectionInformation, DatabaseLeaderElectionConfiguration leaderElectionConfiguration) {
        super(leaderElectionInformation, leaderElectionConfiguration);
        this.electionTask = null;
        this.leaderElectionDAO = new LeaderElectionDAO(getLeaderElectionConfiguration());
        this.currentLeaderRecordId = null;
        this.consecutiveFailures = 0;
        this.backoffUntil = NANOS_NOT_SET;
        this.lastRenewalNanos = NANOS_NOT_SET;
        this.dbClockOffsetMillis = 0;
        this.lastClockSyncAt = 0;
        this.lastSuccessfulClockSyncAt = 0;
        this.closing = new AtomicBoolean(false);
        this.sessionCounter = new AtomicInteger(0);
        this.daoReady = false;
    }


    /**
     * Initialise the database schema (CREATE TABLE, migrations). Idempotent: subsequent calls
     * before {@link #close()} are no-ops. Called automatically by the factory at build time so
     * schema errors surface immediately rather than on the first {@link #elect()} call.
     * {@link #initializeImplementation()} also calls this method so that electors created outside
     * the factory (e.g. in tests) are handled correctly, and to cover the re-election case after
     * {@link #close()} has reset the flag.
     *
     * <p>The DAO call runs on a dedicated thread so the JDBC connection is never JTA-enlisted,
     * even when {@code build()} or {@code getLeaderElection()} is called inside a
     * container-managed transaction (e.g. a {@code @Transactional} startup method).
     * JTA context is thread-local; the fresh thread has no active transaction, so
     * {@code commitIfNeeded()} after DDL succeeds regardless of the caller's context.
     *
     * @throws LeaderElectionException if schema creation or migration fails
     */
    public void initializeSchema() throws LeaderElectionException {
        if (schemaReady.get()) {
            return;
        }
        synchronized (schemaInitLock) {
            if (schemaReady.get()) {
                return;
            }
            DataSource dataSource = getLeaderElectionConfiguration().getDataSource();
            final String schemaKey = getSchemaKey();
            if (isSchemaValidated(dataSource, schemaKey)) {
                schemaReady.set(true);
                return;
            }
            // Run on a dedicated thread so the connection borrowed from the DataSource is not
            // JTA-enlisted when build() is called inside a container-managed transaction.
            AtomicReference<Exception> error = new AtomicReference<>();
            Thread initThread = new Thread(() -> {
                try {
                    leaderElectionDAO.init();
                } catch (Exception e) {
                    error.set(e);
                }
            }, "leader-election-schema-init");
            initThread.setDaemon(true);
            initThread.start();
            try {
                initThread.join(SCHEMA_INIT_TIMEOUT_MILLIS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new LeaderElectionException("Schema initialisation interrupted", e);
            }
            if (initThread.isAlive()) {
                initThread.interrupt();
                throw new LeaderElectionException("Schema initialisation timed out after " + SCHEMA_INIT_TIMEOUT_MILLIS + "ms. "
                        + "Check database connectivity and whether a DDL lock is held by another session.");
            }
            if (error.get() != null) {
                Exception cause = error.get();
                throw new LeaderElectionException("Could not prepare database for leader election: " + cause.getMessage(), cause);
            }
            markSchemaValidated(dataSource, schemaKey);
            // after the initialization the structure is known, so later electors with the explicit structure find it, too
            markSchemaValidated(dataSource, getSchemaKey());
            schemaReady.set(true);
        }
    }


    /**
     * Gets the key of the validated schema: the structure and table name, or a marker for an auto-detected structure.
     *
     * @return the schema key
     */
    private String getSchemaKey() {
        DatabaseLeaderElectionConfiguration.LeaderElectionDatabaseStructure structure = getLeaderElectionConfiguration().getLeaderElectionDatabaseStructure();
        if (structure == null) {
            return AUTO_DETECTED_SCHEMA;
        }
        return structure.getClass().getName() + ":" + structure.getTableName();
    }


    /**
     * Test if the schema of a data source was already validated.
     *
     * @param dataSource the data source
     * @param schemaKey the schema key
     * @return true if the schema was already validated
     */
    private static boolean isSchemaValidated(DataSource dataSource, String schemaKey) {
        if (dataSource == null) {
            return false;
        }
        Set<String> schemas = VALIDATED_SCHEMAS.get(dataSource);
        return schemas != null && schemas.contains(schemaKey);
    }


    /**
     * Remember that the schema of a data source is validated.
     *
     * @param dataSource the data source
     * @param schemaKey the schema key
     */
    private static void markSchemaValidated(DataSource dataSource, String schemaKey) {
        if (dataSource != null) {
            VALIDATED_SCHEMAS.computeIfAbsent(dataSource, k -> ConcurrentHashMap.newKeySet()).add(schemaKey);
        }
    }


    /**
     * @see com.github.toolarium.leader.election.impl.AbstractLeaderElectorImpl#initializeImplementation()
     */
    @Override
    protected void initializeImplementation() throws LeaderElectionException {
        // Allow reuse after close().
        closing.set(false);
        daoReady = false;                 // reset until init() succeeds below
        lastClockSyncAt = 0;              // force a fresh DB clock sync on (re-)initialization
        lastSuccessfulClockSyncAt = 0;    // reset so staleness bound starts fresh
        lastRenewalNanos = NANOS_NOT_SET; // reset nanoTime sentinel so step-down guards are fresh
        backoffUntil = NANOS_NOT_SET;     // reset backoff on re-initialization
        consecutiveFailures = 0;          // reset failure count so log level and backoff start fresh
        failureReported = false;
        lastWrittenRecordId = null;       // nothing written in this session yet

        LeaderElectionInformation info = getLeaderElectionInformation();
        if (info.getName() == null || info.getName().isBlank()) {
            throw new LeaderElectionException("LeaderElectionInformation.name must not be blank; set it via Builder.name(\"your-app\").");
        }
        getLeaderElectionConfiguration().validateDataSource();

        initializeSchema();  // idempotent: no-op if already called by the factory at build time
        daoReady = true;

        String electionGroupName = getLeaderElectionInformation().getElectionGroupName();
        long retryMillis = getLeaderElectionConfiguration().getRetryPeriod().toMillis();
        // The first tick runs immediately on a shared worker thread, so runIfLeader() callers are not blocked for a DB round-trip at startup.
        // elect() blocks on electionLatch.await() until the first tick calls setLeader(), which counts down the latch: the result is visible
        // as soon as the DB responds. Ticks of this elector never overlap; a tick that is still running when the next one is due skips it.
        electionTask = LeaderElectionExecutors.getInstance().schedulePeriodic("leader-election-db-" + electionGroupName, new DatabaseElectionHandler(), retryMillis);

        // A round that hangs in the database (e.g. a connection pool without a connection timeout) would block elect() without
        // a timeout for good: after the renew deadline elect() returns "not leader"; a later successful round takes the lead.
        final int mySession = sessionCounter.get();
        LeaderElectionExecutors.getInstance().scheduleOnce(() -> {
            synchronized (DatabaseLeaderElectorImpl.this) {
                if (mySession == sessionCounter.get() && !closing.get() && !hasElectionResult()) {
                    LOG.info("The first election round of [{}] did not finish within the renew deadline, this node does not lead for now. "
                             + "The election goes on and this node can still take the lead.", electionGroupName);
                    setLeader(false, null);
                }
            }
        }, getLeaderElectionConfiguration().getRenewDeadline().toMillis());
    }


    /**
     * @see com.github.toolarium.leader.election.impl.AbstractLeaderElectorImpl#extendLeaseNow()
     *     The DB write is submitted to a shared worker thread so that the renewal connection is
     *     borrowed outside any active JTA transaction on the caller's thread, allowing
     *     tryExtend() to be safely called from within a container-managed transaction. It does not
     *     wait behind a running election tick of this elector.
     *     runAsExtension() is invoked on the worker thread so that the extendingInProgress
     *     ThreadLocal is set on the same thread that calls setLeader(), keeping the
     *     leadership-term counter correct.
     */
    @Override
    protected boolean extendLeaseNow() throws LeaderElectionException {
        final int mySession = sessionCounter.get();
        if (closing.get() || electionTask == null) {
            return false;
        }
        // Budget = renewDeadline: the extension runs at most two statements (clock sync and update),
        // each bounded to renewDeadline/3; a lease that cannot be extended within the renew deadline is lost anyway.
        long timeoutMillis = getLeaderElectionConfiguration().getRenewDeadline().toMillis();
        Future<Boolean> future;
        try {
            synchronized (extensionLock) {
                if (pendingExtension != null && !pendingExtension.isDone() && pendingExtensionSession == mySession) {
                    future = pendingExtension;
                } else {
                    future = LeaderElectionExecutors.getInstance().submit("leader-election-db-extend-" + getLeaderElectionInformation().getElectionGroupName(),
                                                                          () -> runAsExtension(() -> doDbExtendLease(mySession)));
                    pendingExtension = future;
                    pendingExtensionSession = mySession;
                }
            }
        } catch (RejectedExecutionException e) {
            return false;
        }
        try {
            return future.get(timeoutMillis, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            // Interrupted (e.g. the lease monitor signalled leadership loss while waiting):
            // restore the flag and return false per the ILeaderLease.tryExtend() contract
            // ("false if the extension failed or leadership was already lost").
            Thread.currentThread().interrupt();
            return false;
        } catch (TimeoutException e) {
            // The extension did not complete within the renew deadline (slow database).
            // Return false per contract instead of throwing. The extension is not cancelled: other callers may wait for it,
            // and a running JDBC call must not be interrupted.
            return false;
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof RejectedExecutionException) {
                // Could not be started; not a DB error.
                return false;
            }
            if (cause instanceof LeaderElectionException) {
                throw (LeaderElectionException) cause;
            }
            throw new LeaderElectionException("Could not extend leader lease: " + cause.getMessage(), e);
        }
    }


    /**
     * Performs the actual DB write for a lease extension. Must only be called from a worker
     * thread (submitted by {@link #extendLeaseNow()}) so that the DataSource connection is
     * borrowed outside any JTA transaction context.
     *
     * <p>An election tick may run at the same time. The result is only applied while the session is
     * unchanged and this node still holds the lead, so an extension never revokes a step-down
     * that happened during its database call.
     *
     * @param mySession the session counter value captured when the extension was requested
     * @return {@code true} if the lease was extended successfully and this node is still the leader
     * @throws LeaderElectionException on DB access errors
     */
    private boolean doDbExtendLease(int mySession) throws LeaderElectionException {
        if (mySession != sessionCounter.get() || !isLeader()) {
            return false;
        }
        String uniqueName = getLeaderElectionInformation().getElectionGroupName();
        String instanceId = getId();
        try {
            long extendStartNanos = System.nanoTime();
            long dbNow = getDbNow();
            LeaderElectorRecord newRecord = new LeaderElectorRecord(uniqueName, instanceId, getLeaderElectionInformation().getUsername(), dbNow);
            int result = leaderElectionDAO.updateLeaderTimestamp(uniqueName, instanceId, newRecord.getId(), newRecord.getTimestamp());
            if (result > 0) {
                lastWrittenRecordId = newRecord.getId();
            }
            // Synchronize on the same intrinsic lock as setLeader/signalLeaderInvalid so that a concurrent close() cannot interleave between the field writes and setLeader(true).
            // Re-read via super.isLeader() on the true-branch in case electionClosed caused setLeader(true) to be refused; in that case return false, not true.
            synchronized (this) {
                if (mySession != sessionCounter.get() || closing.get() || !super.isLeader()) {
                    // closed, re-initialized or stepped down while the update was running
                    return false;
                }
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
     *     Overrides the cached boolean with a monotonic-clock expiry check so that a stuck election thread (e.g. blocked network I/O) cannot keep returning
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
        // Serialize with initializeImplementation(): without this, a close() racing the first elect()
        // could see electionTask == null while initialization is about to schedule the ticks,
        // leaking a periodic task and leaving the elector in a state where every later close()
        // returns immediately (closing already true) and elect() never gets a result.
        // The wait covers a schema initialisation in progress (bounded by SCHEMA_INIT_TIMEOUT_MILLIS)
        // plus the normal close budget.
        closeSerialized(SCHEMA_INIT_TIMEOUT_MILLIS + getLeaderElectionConfiguration().getCloseTimeout().toMillis(), this::closeNow);
    }


    /**
     * Performs the actual close; called by {@link #close()} while holding the transition lock.
     *
     * @throws LeaderElectionException In case of an error
     */
    private void closeNow() throws LeaderElectionException {
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
        // has consumed the whole budget, leaving the task un-interruptible and the row deleted
        // while the task is still running.
        signalLeaderInvalid();

        // Compute a single deadline for the whole close() operation so the scheduler wait
        // and the task-cleanup wait together do not exceed closeTimeout.
        long closeMillis = getLeaderElectionConfiguration().getCloseTimeout().toMillis();
        long closeDeadlineNanos = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(closeMillis);

        // Stop the periodic ticks and wait for a running tick of this elector within the close budget.
        // Capture into a local to avoid a TOCTOU race on the volatile field between the null-check and the calls.
        LeaderElectionExecutors.PeriodicTask task = electionTask;
        if (task != null) {
            task.cancel();
            try {
                long remainingMs = TimeUnit.NANOSECONDS.toMillis(closeDeadlineNanos - System.nanoTime());
                if (remainingMs > 0) {
                    task.awaitIdle(remainingMs);
                }
            } catch (InterruptedException e) {
                // Restore the interrupt flag so the calling thread (e.g. a framework shutdown
                // thread) can observe that it was interrupted and react accordingly.
                Thread.currentThread().interrupt();
            }
            electionTask = null;
        }

        // A running extension finds the session changed and discards its result; its
        // conditional UPDATE never re-creates a row deleted below.

        // Signal running tasks and wait for them to finish using the remaining budget.
        // This prevents a window where the DB row is already gone (another node can insert)
        // while our task still holds the lease as valid.
        long remainingForSuper = TimeUnit.NANOSECONDS.toMillis(closeDeadlineNanos - System.nanoTime());
        boolean tasksFinished = false;
        try {
            tasksFinished = super.closeInternal(wasLeader, Math.max(0L, remainingForSuper));
        } finally {
            // Attempt to delete the own leader record so another node can take over immediately.
            // The DELETE targets this node's instance column and is a no-op when the row does
            // not exist or belongs to another node. We do NOT guard on currentLeaderRecordId:
            // a tick running concurrently during close() may have renewed the row while also
            // setting currentLeaderRecordId=null (closing branch), leaving the row in the
            // table even though this elector is no longer active.
            // Guard on daoReady: if elect() was never called or init() failed (e.g. allowDdl=false
            // with a missing table), skip the DELETE silently; there is no row to delete.
            //
            // The DELETE runs on a daemon thread joined for at most the remaining closeTimeout
            // budget.  A hanging database cannot cause close() to block beyond closeTimeout;
            // if the DELETE does not finish in time the row expires naturally after `timeout`.
            final String recordToDelete = lastWrittenRecordId;
            if (daoReady && recordToDelete != null && !tasksFinished) {
                // A leader task is still running: deleting the row now would let another node take the lead while this node still
                // does leader work. The row is deleted once the tasks ended, unless the elector was elected again meanwhile.
                final int closedSession = sessionCounter.get();
                final String groupName = getLeaderElectionInformation().getElectionGroupName();
                final String instanceId = getId();
                releaseWhenTasksFinished(false, "leader-election-db-release-" + getLeaderElectionInformation().getName(), () -> {
                    if (closedSession != sessionCounter.get() || isInitialized()) {
                        return;
                    }
                    try {
                        leaderElectionDAO.deleteLeaderRecord(groupName, instanceId, recordToDelete);
                    } catch (Exception e) {
                        LOG.info("Could not delete leader record for [{}]: {}, the row will expire after the configured timeout.", groupName, e.getMessage());
                    }
                });
            } else if (daoReady && recordToDelete != null) {
                // The row is only deleted while it still holds the record written last by this session: a delete that is late
                // (e.g. waiting for a connection) cannot remove a row the elector wrote after it was elected again.
                final String groupName = getLeaderElectionInformation().getElectionGroupName();
                final String instanceId = getId();
                Thread deleteThread = new Thread(() -> {
                    try {
                        leaderElectionDAO.deleteLeaderRecord(groupName, instanceId, recordToDelete);
                    } catch (Exception e) {
                        if (LOG.isDebugEnabled()) {
                            LOG.debug("Error detail:", e);
                        }
                        LOG.info("Could not delete leader record for [{}]: {}, the row will expire after the configured timeout. "
                                + "To avoid this delay close the leader elector before the DataSource is destroyed (e.g. @PreDestroy).",
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
                    deleteThread.interrupt();
                    LOG.info("Leader record for [{}] could not be deleted before shutdown timeout; "
                            + "the row will expire after the configured timeout. "
                            + "To avoid this delay close the leader elector before the DataSource is destroyed (e.g. @PreDestroy).",
                            groupName);
                }
            }
            // All other writes to currentLeaderRecordId are under synchronized(this); keep the
            // convention here even though the scheduler is already stopped at this point.
            synchronized (this) {
                currentLeaderRecordId = null;
            }
            // Reset daoReady so that the flag accurately reflects the closed state between
            // this close() and a future elect() call.  initializeImplementation() sets it
            // back to true once init() succeeds, so re-election is unaffected.
            daoReady = false;
            // Reset schemaReady so that a future elect() after close() re-validates and
            // re-migrates the schema. initializeSchema() sets it back to true on success.
            schemaReady.set(false);

            leaderElectionDAO.close();
        }
    }


    /**
     * Query the database for all currently stored leader records and return them as read-only views.
     * Each row in the table represents one election group's current leader, including leaders held
     * by other nodes in the cluster. Local leaders (this JVM) are included as well; the caller
     * (LeaderElectionScheduler) deduplicates by id.
     *
     * <p><b>Data exposure:</b> each view reflects raw database column values, including the OS
     * username stored in the {@code username} column by each cluster node. Callers that log or
     * display view fields should be aware that this data originates from the database and may
     * include information contributed by other nodes.
     *
     * <p>Results are capped at {@value #MAX_ELECTOR_ROWS} rows; an INFO message is logged if the table
     * exceeds that limit.
     *
     * @return unmodifiable collection of views; never {@code null}
     */
    public Collection<ILeaderElectorView> getDatabaseElectorViews() {
        try {
            List<LeaderElectorRecord> records = leaderElectionDAO.selectAll(MAX_ELECTOR_ROWS + 1);
            if (records.size() > MAX_ELECTOR_ROWS) {
                LOG.info("Leader-election view truncated to {} rows; the table contains more rows than the display cap.", MAX_ELECTOR_ROWS);
                records = records.subList(0, MAX_ELECTOR_ROWS);
            }
            List<ILeaderElectorView> views = new ArrayList<>(records.size());
            for (LeaderElectorRecord record : records) {
                views.add(new DatabaseRecordView(record));
            }
            return Collections.unmodifiableList(views);
        } catch (Exception e) {
            LOG.info("Could not query stored leader records: {}, no action required, result will be retried on next request.", e.getMessage());
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
     * the new leader acquires only after the old holder has already stepped down; no split-brain.
     *
     * <p><b>Handover:</b> the released node does not compete for the lead until one {@code timeout} after the deadline,
     * so another node takes over. If no other node takes the lead in that time, the released node takes it back.
     *
     * <p><b>Access control:</b> this method is privileged; it can disrupt leadership for any
     * remote node in the cluster. The framework provides no built-in authorisation check; the
     * caller is responsible for ensuring that only authorised principals (e.g. administrators)
     * can invoke it.
     *
     * @param instanceId the stable instance id ({@link ILeaderElectorView#getId()}) of the remote leader
     * @return {@code true} if a matching record was found and the deadline was set
     * @throws IllegalArgumentException if {@code instanceId} is null or blank
     */
    public boolean forceReleaseRemote(String instanceId) {
        if (instanceId == null || instanceId.isBlank()) {
            throw new IllegalArgumentException("instanceId must not be null or blank");
        }
        try {
            long renewDeadlineMillis = getLeaderElectionConfiguration().getRenewDeadline().toMillis();
            long deadline;
            try {
                deadline = leaderElectionDAO.getDatabaseCurrentTimeMillis() + renewDeadlineMillis;
            } catch (Exception e) {
                LOG.info("Could not fetch DB clock for force-release [{}], falling back to local clock with cached offset: {}",
                        instanceId, e.getMessage());
                deadline = System.currentTimeMillis() + dbClockOffsetMillis + renewDeadlineMillis;
            }
            int updated = leaderElectionDAO.setForceReleaseDeadline(instanceId, deadline);
            if (updated > 0) {
                LOG.info("Force-release deadline set for remote leader instance [{}] (deadline in {}ms).", instanceId, renewDeadlineMillis);
                return true;
            }
        } catch (Exception e) {
            LOG.info("Could not set force-release deadline for remote leader [{}]: {}, no action required, the lease will expire automatically after the configured timeout.", instanceId, e.getMessage());
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
     * @param mySession the session counter value captured when the handler was created
     * @return {@code true} if the caller should return immediately (leadership was relinquished)
     */
    private boolean checkPreTickRenewDeadline(String uniqueName, int mySession) {
        if (super.isLeader() && lastRenewalNanos != NANOS_NOT_SET) {
            if (System.nanoTime() - lastRenewalNanos > getLeaderElectionConfiguration().getRenewDeadline().toNanos()) {
                synchronized (this) {
                    if (mySession != sessionCounter.get() || closing.get()) {
                        return true;
                    }
                    LOG.info("Renew deadline exceeded (pre-tick) for [{}], stepping down as leader.", uniqueName);
                    currentLeaderRecordId = null;
                    setLeader(false, null);
                }
                // Row stays in the DB; handleOwnLeaderTick() will re-acquire it once it
                // has expired, keeping the behaviour consistent with the non-pre-tick path.
                return true;
            }
        }
        return false;
    }


    /**
     * Renews the lease of the current leader with a single conditional UPDATE, without reading the row first.
     * The UPDATE only matches while the row still belongs to this instance and no force-release is requested.
     *
     * @param mySession       session counter value captured when the handler was created
     * @param roundStartNanos nanoTime captured at the very start of the tick (before getDbNow)
     * @param dbNow           current DB-clock epoch milliseconds
     * @param uniqueName      the election group name
     * @param instanceId      this node's instance identifier
     * @return {@code true} if the round is complete; {@code false} if no row matched and the caller must read the row
     * @throws Exception on DB access errors
     */
    private boolean renewOwnLease(int mySession, long roundStartNanos, long dbNow, String uniqueName, String instanceId) throws Exception {
        LeaderElectorRecord newRecord = new LeaderElectorRecord(uniqueName, instanceId, getLeaderElectionInformation().getUsername(), dbNow);
        int result = leaderElectionDAO.updateLeaderTimestamp(uniqueName, instanceId, newRecord.getId(), newRecord.getTimestamp());
        if (result > 0) {
            lastWrittenRecordId = newRecord.getId();
        }
        if (result <= 0) {
            return false;
        }

        // Session check and state update run under the same intrinsic lock as signalLeaderInvalid() so that
        // close()+elect() cannot interleave between the check and the setLeader(true)/field writes.
        synchronized (this) {
            if (mySession != sessionCounter.get() || closing.get() || !super.isLeader()) {
                // closed, re-initialized or stepped down during the UPDATE: the next round reads the row
                return true;
            }
            currentLeaderRecordId = newRecord.getId();
            lastRenewalNanos = roundStartNanos;
            setLeader(true, null);
        }
        return true;
    }


    /**
     * Resets the failure count and the back-off after a successful round. A round of a previous session
     * must not clear the failure count or back-off of the current session, so the reset runs under the
     * intrinsic lock with a session check.
     *
     * @param mySession session counter value captured when the handler was created
     */
    private void resetFailures(int mySession) {
        int recoveredAfter = 0;
        synchronized (this) {
            if (mySession == sessionCounter.get() && !closing.get()) {
                if (failureReported) {
                    recoveredAfter = consecutiveFailures;
                    failureReported = false;
                }
                consecutiveFailures = 0;
                backoffUntil = NANOS_NOT_SET;
            }
        }
        if (recoveredAfter > 0) {
            LOG.info("Database leader election of [{}] works again after {} failed rounds.", getLeaderElectionInformation().getElectionGroupName(), recoveredAfter);
        }
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
                if (mySession != sessionCounter.get() || closing.get()) {
                    return true;
                }
                setLeader(false, null);
            }
            long timeout = getLeaderElectionConfiguration().getTimeout().toMillis();
            if (frd != 0 && dbNow < frd + timeout) {
                // An admin released this node: until the deadline the row guards against any acquisition, after the deadline
                // other nodes may take it over. This node stays away for one more lease period so that another node takes the
                // lead instead of this one winning it back; only if no other node took over in that time, it takes the lead back.
                synchronized (this) {
                    currentLeaderRecordId = null;
                }
            } else {
                // Check whether the lease has already expired. If so, re-acquire it immediately
                // using the same conditional UPDATE as stealLeadership so no other node wins the
                // race. This fixes the case where the DB was unreachable when the pre-tick guard
                // stepped us down: the delete failed, the row stayed, and without this path we
                // would loop here forever with isLeader()=false and no one taking over.
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
                    if (result > 0) {
                        lastWrittenRecordId = newRecord.getId();
                    }
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
                    // Lease still within timeout window; keep the row as a guard until it ages
                    // out, giving any in-flight runIfLeader task the (timeout - renewDeadline)
                    // window to notice and stop before another node can acquire.
                    synchronized (this) {
                        currentLeaderRecordId = null;
                    }
                }
            }
        } else if (frd != 0) {
            // Admin requested force-release - step down without renewing.
            // Do NOT delete the row: the deadline guards new acquisitions until frd elapses,
            // preventing a split-brain between our (soon-to-expire) lease and the new leader.
            synchronized (this) {
                if (mySession != sessionCounter.get() || closing.get()) {
                    return true;
                }
                LOG.info("Force-release deadline detected for [{}], stepping down as leader.", uniqueName);
                currentLeaderRecordId = null;
                setLeader(false, null);
            }
        } else {
            // We are the leader; atomically renew the lease using the DB clock.
            // The UPDATE condition checks force_release_deadline IS NULL so a concurrent
            // forceRelease that set the deadline between our SELECT and UPDATE cannot be bypassed.
            LeaderElectorRecord newRecord = new LeaderElectorRecord(uniqueName, instanceId, getLeaderElectionInformation().getUsername(), dbNow);
            int result = leaderElectionDAO.updateLeaderTimestamp(uniqueName, instanceId, newRecord.getId(), newRecord.getTimestamp());
            if (result > 0) {
                lastWrittenRecordId = newRecord.getId();
            }
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
     * <p>Errors of a previous session (a tick still running after {@code close()}) or during
     * {@code close()} are only logged at debug level and do not change the current session's state.
     *
     * @param e          the throwable
     * @param uniqueName the election group name
     * @param mySession  the session counter value captured when the failing handler was created
     */
    private void handleTickError(Throwable e, String uniqueName, int mySession) {
        int failures;
        boolean alreadyReported;
        boolean report = false;
        synchronized (this) {
            if (mySession != sessionCounter.get() || closing.get()) {
                if (LOG.isDebugEnabled()) {
                    LOG.debug("Ignore error of a closed election session of [{}]: {}", uniqueName, e.getMessage());
                }
                return;
            }
            consecutiveFailures++;
            failures = consecutiveFailures;
            alreadyReported = failureReported;
            if (!alreadyReported && (e instanceof Error || failures >= CONSECUTIVE_FAILURE_THRESHOLD)) {
                failureReported = true;
                report = true;
            }
        }

        // One warning per outage: single failures happen now and then, and a lasting outage must not flood the log.
        // The scheduler is kept alive in any case, the next round retries.
        if (LOG.isDebugEnabled()) {
            LOG.debug("Election round of [{}] failed: {}", uniqueName, e.toString(), e);
        }
        if (report) {
            LOG.warn("Database leader election of [{}] failed {} times in a row: {}. The node cannot lead until the database answers again; "
                     + "check the database, the connection pool and the credentials.", uniqueName, failures, e.toString());
        } else if (!alreadyReported) {
            LOG.info("Error occurred while verifying database cluster [{}]: {}", uniqueName, e.getMessage());
        }

        // State changes run under the intrinsic lock with a fresh session check, so a close()+elect()
        // cycle during the logging above cannot let this error release the new session's latch or set its backoff.
        synchronized (this) {
            if (mySession != sessionCounter.get() || closing.get()) {
                return;
            }

            // If we hold leadership but haven't successfully renewed within the renew deadline,
            // voluntarily step down so another node can take over before the full timeout elapses.
            // Uses super.isLeader() (stored flag), not the overridden isLeader(), for the same
            // reason as checkPreTickRenewDeadline: the nanoTime check may already be false.
            // Uses nanoTime (same as the isLeader() override and checkPreTickRenewDeadline) so that
            // a wall-clock jump cannot trigger a spurious step-down.
            if (super.isLeader() && lastRenewalNanos != NANOS_NOT_SET
                    && System.nanoTime() - lastRenewalNanos > getLeaderElectionConfiguration().getRenewDeadline().toNanos()) {
                LOG.info("Renew deadline exceeded for [{}], stepping down as leader.", uniqueName);
                currentLeaderRecordId = null;
                setLeader(false, null);
            }

            // Ensure elect() can return even when the very first round fails with a DB error.
            if (!hasElectionResult()) {
                setLeader(false, null);
            }

            scheduleBackoff(failures);
        }
    }


    /**
     * Schedules the exponential back-off after a failed tick. Must be called under the intrinsic lock.
     *
     * @param failures the number of consecutive failures including the current one
     */
    private void scheduleBackoff(int failures) {
        // Cap the backoff so it never pushes the next attempt past the renew deadline.
        // Both candidate and cap use System.nanoTime() so a wall-clock jump backward
        // (NTP correction, VM resume) cannot extend the backoff window unexpectedly.
        long retryPeriodNanos = getLeaderElectionConfiguration().getRetryPeriod().toNanos();
        long exp = 1L << Math.min(failures - 1, 5);
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
     * on first call) so the tick can still proceed; if the DB is truly unreachable the
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

        // Read the sync timestamps under the lock, then perform the DB call outside it so
        // that a blocked DB round-trip never prevents close()/signalLeaderInvalid() from
        // acquiring synchronized(this).
        boolean needsRefresh;
        boolean everSynced;
        synchronized (clockSyncLock) {
            // needsRefresh uses lastClockSyncAt (concurrency guard): 0 means no refresh is
            // currently claimed; non-zero means one is in flight or recently completed.
            needsRefresh = lastClockSyncAt == 0 || now - lastClockSyncAt > refreshThresholdMs;
            // everSynced uses lastSuccessfulClockSyncAt: only set after a DB call succeeds.
            // This separates "has a sync ever worked" from "is a sync currently in flight".
            everSynced = lastSuccessfulClockSyncAt != 0;
            if (needsRefresh) {
                // Claim the refresh slot optimistically so concurrent callers (e.g. the
                // scheduler thread and a runIfLeader caller in extendLeaseNow()) that both
                // see needsRefresh=true do not each issue a redundant DB round-trip (TS-1).
                // On DB failure lastClockSyncAt is reset to 0 so the next tick retries.
                lastClockSyncAt = now;
            }
        }
        if (needsRefresh) {
            try {
                long dbNow = leaderElectionDAO.getDatabaseCurrentTimeMillis();
                synchronized (clockSyncLock) {
                    dbClockOffsetMillis = dbNow - now;
                    lastClockSyncAt = now;
                    lastSuccessfulClockSyncAt = now;
                }
                return dbNow;
            } catch (Exception e) {
                synchronized (clockSyncLock) {
                    // Reset concurrency guard so the next tick can attempt the refresh.
                    lastClockSyncAt = 0;
                    // lastSuccessfulClockSyncAt is NOT touched: the staleness bound and the
                    // "initial vs retry" log decision must reflect the last successful sync,
                    // not the failed attempt (Finding 4).
                }
                if (everSynced) {
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

        synchronized (clockSyncLock) {
            // Staleness bound: use lastSuccessfulClockSyncAt so the clock drift guard fires
            // correctly during a prolonged DB outage (Finding 4). Resetting lastClockSyncAt=0
            // on each failed attempt must not prevent the bound from being evaluated.
            if (lastSuccessfulClockSyncAt != 0 && now - lastSuccessfulClockSyncAt > stalenessThresholdMs) {
                if (LOG.isDebugEnabled()) {
                    LOG.debug("DB clock offset for [{}] is stale ({}ms since last successful sync), resetting to zero (local clock).",
                            getLeaderElectionInformation().getElectionGroupName(), now - lastSuccessfulClockSyncAt);
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
            // super.isLeader() (stored flag) is used there, not the overridden isLeader(), so the
            // guard fires even when the nanoTime expiry already returned false on the previous call.
            if (checkPreTickRenewDeadline(uniqueName, mySession)) {
                return;
            }

            // Track whether THIS round wrote a new row (insert or steal from another instance).
            // Used by the finally block: only rows created by THIS round are deleted there.
            // Rows that existed before close() ran (renewals) are left for close() to remove
            // AFTER closeInternal() finishes waiting for running runIfLeader tasks, preventing
            // a split-brain window where another node acquires before this node's task stops.
            boolean rowWrittenThisRound = false;
            String recordWrittenThisRound = null;
            try {
                // Capture local nanoTime at the very start of the round so that lastRenewalNanos is never set to a time
                // AFTER the DB write; using nanoTime() after the DB call would extend the local lease by the round-trip
                // duration, potentially creating a split-brain window.
                long roundStartNanos = System.nanoTime();
                // Obtain a DB-clock-anchored timestamp (cached offset, refreshed every CLOCK_SYNC_TICKS ticks) so all
                // writes and expiry checks use the same reference point without an extra DB round-trip on most ticks.
                long dbNow = getDbNow();
                // Steady state: the leader renews with a single conditional UPDATE. The SELECT below only runs
                // when this node is not the leader or the UPDATE matched no row (row taken over or deleted,
                // force-release requested).
                if (isLeader() && renewOwnLease(mySession, roundStartNanos, dbNow, uniqueName, instanceId)) {
                    resetFailures(mySession);
                    return;
                }
                LeaderElectorRecord currentLeader = leaderElectionDAO.selectLeaderByName(uniqueName);
                // Mid-round session/closing guard: a round blocked in selectLeaderByName during a
                // close()+elect() cycle must not contaminate the new session's state.
                if (mySession != sessionCounter.get() || closing.get()) {
                    return;
                }

                if (currentLeader == null) {
                    // No leader exists; try to become one.
                    LeaderElectorRecord newRecord = new LeaderElectorRecord(uniqueName, instanceId, getLeaderElectionInformation().getUsername(), dbNow);
                    int result = leaderElectionDAO.insertLeader(newRecord);
                    if (result > 0) {
                        lastWrittenRecordId = newRecord.getId();
                    }
                    if (result > 0) {
                        rowWrittenThisRound = true;
                        recordWrittenThisRound = newRecord.getId();
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
                    // Another instance holds leadership - check expiry and force-release deadline.
                    long timeout = getLeaderElectionConfiguration().getTimeout().toMillis();
                    long expiryThreshold = dbNow - timeout;
                    boolean leaseExpired = currentLeader.getTimestamp() < expiryThreshold;
                    long frd = currentLeader.getForceReleaseDeadline();
                    boolean forceReleaseReady = frd != 0 && frd <= dbNow;

                    if (leaseExpired || forceReleaseReady) {
                        // Lease expired or force-release deadline elapsed - atomically steal leadership.
                        // Best-effort pre-allocation guard: skip LeaderElectorRecord allocation when
                        // closing or session already stale. Authoritative check is inside synchronized.
                        if (closing.get() || mySession != sessionCounter.get()) {
                            return;
                        }
                        LeaderElectorRecord newRecord = new LeaderElectorRecord(uniqueName, instanceId, getLeaderElectionInformation().getUsername(), dbNow);
                        int result = leaderElectionDAO.stealLeadership(uniqueName, currentLeader.getId(), expiryThreshold, dbNow, newRecord);
                        if (result > 0) {
                            lastWrittenRecordId = newRecord.getId();
                        }
                        if (result > 0) {
                            rowWrittenThisRound = true;
                            recordWrittenThisRound = newRecord.getId();
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
                            if (mySession != sessionCounter.get() || closing.get()) {
                                return;
                            }
                            setLeader(false, null);
                        }
                    }
                }
                resetFailures(mySession);
            } catch (Throwable e) {
                // handleTickError() logs, steps down if renewDeadline is exceeded, and schedules
                // backoff.  We intentionally do NOT re-throw Errors: with scheduleAtFixedRate a
                // thrown exception cancels ALL future executions, so re-throwing a transient Error
                // (e.g. OOM during a load peak, StackOverflowError in a JDBC driver) would
                // permanently stop this elector without any further recovery attempt.  The ERROR
                // log from handleTickError() is the operator's signal to investigate.
                handleTickError(e, uniqueName, mySession);
            } finally {
                if (rowWrittenThisRound) {
                    deleteRowOfClosedSession(uniqueName, instanceId, recordWrittenThisRound);
                }
            }
        }


        /**
         * If close() is in progress OR this round belongs to a stale session (close()+elect() cycled while blocked in a DB call),
         * delete the row this round wrote (insert or steal from another instance), so other nodes are not delayed by the full
         * timeout. Renewal rows (updateLeaderTimestamp) are excluded: they existed before close() ran and are removed by close()
         * itself only after closeInternal() has waited for all running runIfLeader tasks to finish, preventing the split-brain overlap.
         *
         * @param uniqueName the election group name
         * @param instanceId this node's instance identifier
         * @param recordId the record id this round wrote
         */
        private void deleteRowOfClosedSession(String uniqueName, String instanceId, String recordId) {
            if (closing.get() || mySession != sessionCounter.get()) {
                try {
                    leaderElectionDAO.deleteLeaderRecord(uniqueName, instanceId, recordId);
                } catch (Exception ex) {
                    // best-effort; the row will expire after timeout
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
            if (base.getElectionGroupName().equals(record.getName())) {
                this.cachedInfo = new LeaderElectionInformation(base.getNamespace(), base.getName(), record.getInstance());
            } else {
                // The table is shared by several election groups; a row of another group must not be
                // labelled with this elector's namespace and name. The stored group name cannot be split
                // back into namespace and name unambiguously (both may contain dots), so it is exposed
                // as the name, which keeps getElectionGroupName() equal to the stored row key.
                this.cachedInfo = new LeaderElectionInformation(null, record.getName(), record.getInstance());
            }
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
