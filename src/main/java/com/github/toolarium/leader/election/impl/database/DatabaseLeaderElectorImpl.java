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
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


/**
 * Implements the {@link ILeaderElector} based on database.
 *
 * @author patrick
 */
public class DatabaseLeaderElectorImpl extends AbstractLeaderElectorImpl<DatabaseLeaderElectionConfiguration> {
    private static final Logger LOG = LoggerFactory.getLogger(DatabaseLeaderElectorImpl.class);
    private static final int CONSECUTIVE_FAILURE_THRESHOLD = 3;
    private static final long MAX_BACKOFF_MILLIS = 60_000L;
    private ScheduledExecutorService scheduledExecuterService;
    private ScheduledFuture<?> scheduledFuture;
    private LeaderElectionDAO leaderElectionDAO;
    private volatile String currentLeaderRecordId;
    private volatile int consecutiveFailures;
    private volatile long backoffUntil;
    private volatile long lastSuccessfulRenewalAt;
    private volatile boolean closing;


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
        this.closing = false;
    }


    /**
     * @see com.github.toolarium.leader.election.impl.AbstractLeaderElectorImpl#initializeImplementation()
     */
    @Override
    protected void initializeImplementation() throws LeaderElectionException {
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

        scheduledExecuterService = Executors.newScheduledThreadPool(1);
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
        LeaderElectorRecord newRecord = new LeaderElectorRecord(uniqueName, instanceId, getLeaderElectionInformation().getUsername());
        try {
            int result = leaderElectionDAO.updateLeaderTimestamp(uniqueName, instanceId, newRecord.getId(), newRecord.getTimestamp());
            if (result > 0) {
                currentLeaderRecordId = newRecord.getId();
                lastSuccessfulRenewalAt = System.currentTimeMillis();
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
     * @see com.github.toolarium.leader.election.ILeaderElector#close()
     */
    @Override
    public void close() throws LeaderElectionException {
        closing = true;

        // Cancel the scheduler and drain any in-flight tick before deleting the DB record.
        // This ensures no concurrent scheduler tick can renew the lease after we delete it.
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

        // Delete own leader record so another node can take over immediately.
        // Use the stable instance id (not the per-renewal record id) so the delete succeeds
        // regardless of how many renewals happened before close() was called.
        if (currentLeaderRecordId != null) {
            try {
                leaderElectionDAO.deleteLeaderByNameAndInstance(getLeaderElectionInformation().getElectionGroupName(), getId());
            } catch (Exception e) {
                if (LOG.isDebugEnabled()) {
                    LOG.debug("Error detail:", e);
                }
                LOG.warn("Could not delete leader record on close: {}", e.getMessage());
            }
            currentLeaderRecordId = null;
        }

        super.close();

        leaderElectionDAO.close();
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
     * This deletes the row from the database so another node can take over immediately.
     * Returns {@code false} if no matching record exists.
     *
     * @param instanceId the stable instance id ({@link ILeaderElectorView#getId()}) of the remote leader
     * @return {@code true} if a matching record was found and deleted
     */
    public boolean forceReleaseRemote(String instanceId) {
        try {
            int deleted = leaderElectionDAO.deleteLeaderByInstance(instanceId);
            if (deleted > 0) {
                LOG.info("Force-released remote leader record for instance [{}].", instanceId);
                return true;
            }
        } catch (Exception e) {
            LOG.warn("Could not force-release remote leader [{}]: {}", instanceId, e.getMessage());
        }
        return false;
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
            if (closing) {
                return;
            }
            // Exponential back-off: skip this tick if we are still within the back-off window.
            if (System.currentTimeMillis() < backoffUntil) {
                return;
            }

            String uniqueName = getLeaderElectionInformation().getElectionGroupName();
            String instanceId = getId();

            try {
                LeaderElectorRecord currentLeader = leaderElectionDAO.selectLeaderByName(uniqueName);

                if (currentLeader == null) {
                    // No leader exists — try to become one.
                    LeaderElectorRecord newRecord = new LeaderElectorRecord(uniqueName, instanceId, getLeaderElectionInformation().getUsername());
                    int result = leaderElectionDAO.insertLeader(newRecord);
                    if (result > 0) {
                        currentLeaderRecordId = newRecord.getId();
                        lastSuccessfulRenewalAt = System.currentTimeMillis();
                        setLeader(true, null);
                    }
                } else if (instanceId.equals(currentLeader.getInstance())) {
                    // We are the leader — atomically renew the lease.
                    LeaderElectorRecord newRecord = new LeaderElectorRecord(uniqueName, instanceId, getLeaderElectionInformation().getUsername());
                    int result = leaderElectionDAO.updateLeaderTimestamp(uniqueName, instanceId, newRecord.getId(), newRecord.getTimestamp());
                    if (result > 0) {
                        currentLeaderRecordId = newRecord.getId();
                        lastSuccessfulRenewalAt = System.currentTimeMillis();
                        setLeader(true, null);
                    } else {
                        currentLeaderRecordId = null;
                        setLeader(false, null);
                    }
                } else {
                    // Another instance holds leadership — use a local-clock fast-path to avoid a DB
                    // round-trip when the lease is clearly still valid.
                    long timeout = getLeaderElectionConfiguration().getTimeout().toMillis();
                    if (currentLeader.getTimestamp() >= System.currentTimeMillis() - timeout) {
                        setLeader(false, null);
                    } else {
                        // Lease appears expired locally — verify using the DB clock to avoid false
                        // expiry caused by node-clock skew.
                        long dbNow = leaderElectionDAO.getDatabaseCurrentTimeMillis();
                        long expiryThreshold = dbNow - timeout;
                        if (currentLeader.getTimestamp() < expiryThreshold) {
                            // Lease expired — atomically steal leadership via a single conditional UPDATE.
                            LeaderElectorRecord newRecord = new LeaderElectorRecord(uniqueName, instanceId, getLeaderElectionInformation().getUsername());
                            int result = leaderElectionDAO.stealLeadership(uniqueName, currentLeader.getId(), expiryThreshold, newRecord);
                            if (result > 0) {
                                currentLeaderRecordId = newRecord.getId();
                                lastSuccessfulRenewalAt = System.currentTimeMillis();
                                setLeader(true, null);
                            } else {
                                setLeader(false, null);
                            }
                        } else {
                            setLeader(false, null);
                        }
                    }
                }
                consecutiveFailures = 0;
                backoffUntil = 0;
            } catch (Exception e) {
                consecutiveFailures++;
                if (consecutiveFailures >= CONSECUTIVE_FAILURE_THRESHOLD) {
                    LOG.error("Repeated failure ({}) verifying database cluster [{}]: {}", consecutiveFailures, uniqueName, e.getMessage());
                } else {
                    LOG.warn("Error occured while verify database cluster [{}]: {}", uniqueName, e.getMessage());
                }
                if (LOG.isDebugEnabled()) {
                    LOG.debug("Error detail:", e);
                }

                // If we hold leadership but haven't successfully renewed within the renew deadline,
                // voluntarily step down so another node can take over before the full timeout elapses.
                long renewDeadlineMillis = getLeaderElectionConfiguration().getRenewDeadline().toMillis();
                if (isLeader() && lastSuccessfulRenewalAt > 0
                        && System.currentTimeMillis() - lastSuccessfulRenewalAt > renewDeadlineMillis) {
                    LOG.info("Renew deadline exceeded for [{}], stepping down as leader.", uniqueName);
                    currentLeaderRecordId = null;
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
