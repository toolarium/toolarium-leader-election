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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


/**
 * Implements the {@link ILeaderElector} based on database.
 *
 * <p><b>Clock synchronisation:</b> all {@code lease_ts} writes use the database server clock
 * ({@code SELECT CURRENT_TIMESTAMP}) so expiry decisions are consistent across nodes regardless
 * of local clock skew.  The internal renew-deadline guard ({@code lastSuccessfulRenewalAt})
 * still uses the local clock for scheduling purposes only and is not persisted to the database.
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
    private ScheduledExecutorService scheduledExecuterService;
    private ScheduledFuture<?> scheduledFuture;
    private LeaderElectionDAO leaderElectionDAO;
    private volatile String currentLeaderRecordId;
    private volatile int consecutiveFailures;
    private volatile long backoffUntil;
    private volatile long lastSuccessfulRenewalAt;
    private volatile long lastRenewalNanos;
    private volatile long dbClockOffsetMillis;
    private volatile long lastClockSyncAt;
    // AtomicBoolean so close() is idempotent under concurrent calls (e.g. app shutdown + JVM hook).
    private final AtomicBoolean closing;


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
        this.backoffUntil = 0;
        this.lastSuccessfulRenewalAt = 0;
        this.lastRenewalNanos = 0L;
        this.dbClockOffsetMillis = 0;
        this.lastClockSyncAt = 0;
        this.closing = new AtomicBoolean(false);
    }


    /**
     * @see com.github.toolarium.leader.election.impl.AbstractLeaderElectorImpl#initializeImplementation()
     */
    @Override
    protected void initializeImplementation() throws LeaderElectionException {
        // Allow reuse after close().
        closing.set(false);
        lastClockSyncAt = 0; // force a fresh DB clock sync on (re-)initialization

        LeaderElectionInformation info = getLeaderElectionInformation();
        if ((info.getNamespace() == null || info.getNamespace().isBlank()) && (info.getName() == null || info.getName().isBlank())) {
            LOG.warn("LeaderElectionInformation has no namespace or name set. Falling back to identity [{}] as the election group key. All nodes must use the same identity value to form a valid election group.", info.getIdentity());
        }

        try {
            leaderElectionDAO.init();
        } catch (SQLException e) {
            throw new LeaderElectionException("Could not prepare database for leader election: " + e.getMessage(), e);
        }

        // Run the first election round synchronously so isLeader() reflects a real result
        // immediately after initialize() returns. A DB error in this initial round is absorbed
        // (same behaviour as a failed scheduled tick) and the periodic task will retry.
        new DatabaseElectionHandler().run();

        String electionGroupName = getLeaderElectionInformation().getElectionGroupName();
        ThreadFactory daemonFactory = runnable -> {
            Thread t = new Thread(runnable, "leader-election-db-" + electionGroupName);
            t.setDaemon(true);
            return t;
        };
        scheduledExecuterService = Executors.newScheduledThreadPool(1, daemonFactory);
        long retryMillis = getLeaderElectionConfiguration().getRetryPeriod().toMillis();
        scheduledFuture = scheduledExecuterService.scheduleAtFixedRate(
                new DatabaseElectionHandler(), retryMillis, retryMillis, TimeUnit.MILLISECONDS);
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
            if (result > 0) {
                currentLeaderRecordId = newRecord.getId();
                lastSuccessfulRenewalAt = System.currentTimeMillis();
                lastRenewalNanos = extendStartNanos;
                setLeader(true, null);
                return true;
            } else {
                currentLeaderRecordId = null;
                setLeader(false, null);
                return false;
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
        if (nanos == 0L) {
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

        // Cancel the scheduler so no new ticks start.
        if (scheduledFuture != null) {
            try {
                scheduledFuture.cancel(false);
            } catch (Exception e) {
                // NOP
            }
            scheduledFuture = null;
        }

        if (scheduledExecuterService != null) {
            try {
                scheduledExecuterService.shutdown();
                long closeMillis = getLeaderElectionConfiguration().getCloseTimeout().toMillis();
                scheduledExecuterService.awaitTermination(closeMillis, java.util.concurrent.TimeUnit.MILLISECONDS);
            } catch (Exception e) {
                // NOP
            }
            scheduledExecuterService = null;
        }

        // Signal running tasks and wait for them to finish (via super.close()), BEFORE
        // releasing the DB record. This prevents a window where the DB row is already
        // gone (another node can insert) while our task still holds the lease as valid.
        try {
            super.close();
        } finally {
            // Delete own leader record so another node can take over immediately.
            if (currentLeaderRecordId != null) {
                try {
                    leaderElectionDAO.deleteLeaderByNameAndInstance(getLeaderElectionInformation().getElectionGroupName(), getId());
                } catch (Exception e) {
                    if (LOG.isDebugEnabled()) {
                        LOG.debug("Error detail:", e);
                    }
                    LOG.info("Could not delete leader record on close for [{}]: {}, the row will expire after the configured timeout. "
                            + "To avoid this delay call close() before the DataSource is destroyed (e.g. @PreDestroy).",
                            getLeaderElectionInformation().getElectionGroupName(), e.getMessage());
                }
                currentLeaderRecordId = null;
            }

            leaderElectionDAO.close();
        }
    }


    /**
     * Query the database for all currently stored leader records and return them as read-only views.
     * Each row in the table represents one election group's current leader — including leaders held
     * by other nodes in the cluster. Local leaders (this JVM) are included as well; the caller
     * (LeaderElectionScheduler) deduplicates by id.
     *
     * @return unmodifiable collection of views; never {@code null}
     */
    public Collection<ILeaderElectorView> getDatabaseElectorViews() {
        try {
            List<LeaderElectorRecord> records = leaderElectionDAO.selectAll();
            List<ILeaderElectorView> views = new ArrayList<>();
            for (LeaderElectorRecord record : records) {
                views.add(new DatabaseRecordView(record));
            }
            return Collections.unmodifiableList(views);
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
     * @param instanceId the stable instance id ({@link ILeaderElectorView#getId()}) of the remote leader
     * @return {@code true} if a matching record was found and the deadline was set
     */
    public boolean forceReleaseRemote(String instanceId) {
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
     * Uses {@code super.isLeader()} (the stored flag from {@code AbstractLeaderElectorImpl})
     * rather than the overridden {@link #isLeader()} so the check fires even when the
     * nanoTime-based expiry already returned {@code false} on the previous call.
     *
     * @param uniqueName the election group name
     * @param instanceId this node's instance identifier
     * @return {@code true} if the caller should return immediately (leadership was relinquished)
     */
    private boolean checkPreTickRenewDeadline(String uniqueName, String instanceId) {
        if (super.isLeader() && lastSuccessfulRenewalAt > 0) {
            long renewDeadlineMillis = getLeaderElectionConfiguration().getRenewDeadline().toMillis();
            if (System.currentTimeMillis() - lastSuccessfulRenewalAt > renewDeadlineMillis) {
                LOG.info("Renew deadline exceeded (pre-tick) for [{}], stepping down as leader.", uniqueName);
                currentLeaderRecordId = null;
                setLeader(false, null);
                try {
                    leaderElectionDAO.deleteLeaderByNameAndInstance(uniqueName, instanceId);
                } catch (Exception del) {
                    LOG.warn("Could not delete leader record during voluntary step-down for [{}]: {}", uniqueName, del.getMessage());
                }
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
     * @param roundStartNanos nanoTime captured at the very start of the tick (before getDbNow)
     * @param dbNow           current DB-clock epoch milliseconds
     * @param uniqueName      the election group name
     * @param instanceId      this node's instance identifier
     * @param frd             the {@code force_release_deadline} value from the DB row (0 = not set)
     * @throws Exception on DB access errors
     */
    private void handleOwnLeaderTick(long roundStartNanos, long dbNow, String uniqueName, String instanceId, long frd) throws Exception {
        if (!isLeader()) {
            // We hold the DB row but have already stepped down locally (renewDeadline exceeded or
            // force-release processed on a previous tick). Ensure the stored flag is false so the
            // term is incremented on the next acquisition.
            setLeader(false, null);
            if (frd != 0 && frd > dbNow) {
                // Force-release deadline still in the future — keep the row as a guard so no
                // other node can acquire before the deadline elapses. Nothing to do this tick.
            } else {
                // Time-based step-down: the local lease expired (renewDeadline exceeded).
                // Do NOT delete the row — let it age out naturally so a new node steals it only
                // after the full timeout, giving any in-flight runIfLeader task the complete
                // (timeout - renewDeadline) window to notice and stop.
                currentLeaderRecordId = null;
            }
        } else if (frd != 0) {
            // Admin requested force-release — step down without renewing.
            // Do NOT delete the row: the deadline guards new acquisitions until frd elapses,
            // preventing a split-brain between our (soon-to-expire) lease and the new leader.
            LOG.info("Force-release deadline detected for [{}], stepping down as leader.", uniqueName);
            currentLeaderRecordId = null;
            setLeader(false, null);
        } else {
            // We are the leader — atomically renew the lease using the DB clock.
            // The UPDATE condition checks force_release_deadline IS NULL so a concurrent
            // forceRelease that set the deadline between our SELECT and UPDATE cannot be bypassed.
            LeaderElectorRecord newRecord = new LeaderElectorRecord(uniqueName, instanceId, getLeaderElectionInformation().getUsername(), dbNow);
            int result = leaderElectionDAO.updateLeaderTimestamp(uniqueName, instanceId, newRecord.getId(), newRecord.getTimestamp());
            if (result > 0) {
                currentLeaderRecordId = newRecord.getId();
                lastSuccessfulRenewalAt = System.currentTimeMillis();
                lastRenewalNanos = roundStartNanos;
                setLeader(true, null);
            } else {
                currentLeaderRecordId = null;
                setLeader(false, null);
            }
        }
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
        long renewDeadlineMillis = getLeaderElectionConfiguration().getRenewDeadline().toMillis();
        if (super.isLeader() && lastSuccessfulRenewalAt > 0
                && System.currentTimeMillis() - lastSuccessfulRenewalAt > renewDeadlineMillis) {
            LOG.info("Renew deadline exceeded for [{}], stepping down as leader.", uniqueName);
            currentLeaderRecordId = null;
            setLeader(false, null);
        }

        // Ensure elect() can return even when the very first round fails with a DB error.
        if (!hasElectionResult()) {
            setLeader(false, null);
        }

        // Cap the backoff so it never pushes the next attempt past the renew deadline.
        long retryPeriod = getLeaderElectionConfiguration().getRetryPeriod().toMillis();
        long exp = (long) Math.pow(2, Math.min(consecutiveFailures - 1, 5));
        long backoffDelay = Math.min(retryPeriod * exp, MAX_BACKOFF_MILLIS);
        long candidate = System.currentTimeMillis() + backoffDelay;
        long renewDeadlineCap;
        if (lastSuccessfulRenewalAt > 0) {
            renewDeadlineCap = lastSuccessfulRenewalAt + renewDeadlineMillis;
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
     * @return epoch milliseconds anchored to the DB server clock (best-effort)
     */
    private long getDbNow() {
        long retryMillis = getLeaderElectionConfiguration().getRetryPeriod().toMillis();
        long now = System.currentTimeMillis();
        if (lastClockSyncAt == 0 || now - lastClockSyncAt > retryMillis * CLOCK_SYNC_TICKS) {
            try {
                long dbNow = leaderElectionDAO.getDatabaseCurrentTimeMillis();
                dbClockOffsetMillis = dbNow - now;
                lastClockSyncAt = now;
                return dbNow;
            } catch (Exception e) {
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
        return now + dbClockOffsetMillis;
    }


    /**
     * The database leader election handler
     *
     * @author patrick
     */
    protected class DatabaseElectionHandler implements Runnable {
        /**
         * @see java.lang.Runnable#run()
         */
        public void run() {
            // Do not execute any more ticks after close() has been called.
            if (closing.get()) {
                return;
            }
            // Exponential back-off: skip this tick if we are still within the back-off window.
            if (System.currentTimeMillis() < backoffUntil) {
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

            try {
                // Capture local nanoTime at the very start of the round so that lastRenewalNanos is never set to a time
                // AFTER the DB write; using nanoTime() after the DB call would extend the local lease by the round-trip
                // duration, potentially creating a split-brain window.
                long roundStartNanos = System.nanoTime();
                // Obtain a DB-clock-anchored timestamp (cached offset, refreshed every CLOCK_SYNC_TICKS ticks) so all
                // writes and expiry checks use the same reference point without an extra DB round-trip on most ticks.
                long dbNow = getDbNow();
                LeaderElectorRecord currentLeader = leaderElectionDAO.selectLeaderByName(uniqueName);

                if (currentLeader == null) {
                    // No leader exists — try to become one. Re-check closing AFTER the SELECT so a tick that was already
                    // running when close() fired cannot re-insert the row that close() just deleted.
                    if (closing.get()) {
                        return;
                    }
                    LeaderElectorRecord newRecord = new LeaderElectorRecord(uniqueName, instanceId, getLeaderElectionInformation().getUsername(), dbNow);
                    int result = leaderElectionDAO.insertLeader(newRecord);
                    if (result > 0) {
                        currentLeaderRecordId = newRecord.getId();
                        lastSuccessfulRenewalAt = System.currentTimeMillis();
                        lastRenewalNanos = roundStartNanos;
                        setLeader(true, null);
                    } else {
                        setLeader(false, null);
                    }
                } else if (instanceId.equals(currentLeader.getInstance())) {
                    handleOwnLeaderTick(roundStartNanos, dbNow, uniqueName, instanceId, currentLeader.getForceReleaseDeadline());
                } else {
                    // Another instance holds leadership — check expiry and force-release deadline.
                    long timeout = getLeaderElectionConfiguration().getTimeout().toMillis();
                    long expiryThreshold = dbNow - timeout;
                    boolean leaseExpired = currentLeader.getTimestamp() < expiryThreshold;
                    long frd = currentLeader.getForceReleaseDeadline();
                    boolean forceReleaseReady = frd != 0 && frd <= dbNow;

                    if (leaseExpired || forceReleaseReady) {
                        // Lease expired or force-release deadline elapsed — atomically steal leadership.
                        // Re-check closing so a tick running through close() does not steal
                        // leadership that was just released.
                        if (closing.get()) {
                            return;
                        }
                        LeaderElectorRecord newRecord = new LeaderElectorRecord(uniqueName, instanceId, getLeaderElectionInformation().getUsername(), dbNow);
                        int result = leaderElectionDAO.stealLeadership(uniqueName, currentLeader.getId(), expiryThreshold, dbNow, newRecord);
                        if (result > 0) {
                            currentLeaderRecordId = newRecord.getId();
                            lastSuccessfulRenewalAt = System.currentTimeMillis();
                            lastRenewalNanos = System.nanoTime();
                            setLeader(true, null);
                        } else {
                            setLeader(false, null);
                        }
                    } else {
                        setLeader(false, null);
                    }
                }
                consecutiveFailures = 0;
                backoffUntil = 0;
            } catch (Throwable e) {
                handleTickError(e, uniqueName);
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


        /**
         * Constructor
         *
         * @param record the database leader record
         */
        DatabaseRecordView(LeaderElectorRecord record) {
            this.record = record;
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
            LeaderElectionInformation base = DatabaseLeaderElectorImpl.this.getLeaderElectionInformation();
            return new LeaderElectionInformation(base.getNamespace(), base.getName(), record.getInstance());
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
