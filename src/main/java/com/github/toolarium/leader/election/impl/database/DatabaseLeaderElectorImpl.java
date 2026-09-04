/*
 * DatabaseLeaderElectorImpl.java
 *
 * Copyright by toolarium, all rights reserved.
 */
package com.github.toolarium.leader.election.impl.database;

import com.github.toolarium.leader.election.LeaderElector;
import com.github.toolarium.leader.election.dto.LeaderElectionInformation;
import com.github.toolarium.leader.election.dto.db.DatabaseLeaderElectionConfiguration;
import com.github.toolarium.leader.election.exception.LeaderElectionException;
import com.github.toolarium.leader.election.impl.AbstractLeaderElectorImpl;
import com.github.toolarium.leader.election.impl.database.dao.LeaderElectionDAO;
import com.github.toolarium.leader.election.impl.database.dao.LeaderElectorRecord;
import java.sql.SQLException;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


/**
 * Implements the {@link LeaderElector} based on database.
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

        scheduledExecuterService = Executors.newScheduledThreadPool(1);
        scheduledFuture = scheduledExecuterService.scheduleAtFixedRate(
                new DatabaseElectionHandler(), 0, getLeaderElectionConfiguration().getRetryPeriod().toMillis(), TimeUnit.MILLISECONDS);
    }


    /**
     * @see com.github.toolarium.leader.election.LeaderElector#close()
     */
    @Override
    public void close() throws LeaderElectionException {
        closing = true;
        // Delete own leader record before closing so another node can take over immediately.
        if (currentLeaderRecordId != null) {
            try {
                leaderElectionDAO.deleteLeaderByNameAndId(
                        getLeaderElectionInformation().getElectionGroupName(), currentLeaderRecordId);
            } catch (Exception e) {
                LOG.warn("Could not delete leader record on close: {}", e.getMessage());
                if (LOG.isDebugEnabled()) {
                    LOG.debug("Error detail:", e);
                }
            }
            currentLeaderRecordId = null;
        }

        super.close();

        if (scheduledFuture != null) {
            try {
                scheduledFuture.cancel(true);
            } catch (Exception e) {
                // NOP
            }
            scheduledFuture = null;
        }

        if (scheduledExecuterService != null) {
            try {
                scheduledExecuterService.shutdown();
            } catch (Exception e) {
                // NOP
            }
            scheduledExecuterService = null;
        }

        leaderElectionDAO.close();
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
                    LeaderElectorRecord newRecord = new LeaderElectorRecord(uniqueName, instanceId, System.getProperty("user.name"));
                    int result = leaderElectionDAO.insertLeader(newRecord);
                    if (result > 0) {
                        currentLeaderRecordId = newRecord.getId();
                        setLeader(true, null);
                    }
                } else if (instanceId.equals(currentLeader.getInstance())) {
                    // We are the leader — atomically renew the lease.
                    LeaderElectorRecord newRecord = new LeaderElectorRecord(uniqueName, instanceId, System.getProperty("user.name"));
                    int result = leaderElectionDAO.updateLeaderTimestamp(uniqueName, instanceId, newRecord.getId(), newRecord.getTimestamp());
                    if (result > 0) {
                        currentLeaderRecordId = newRecord.getId();
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
                            LeaderElectorRecord newRecord = new LeaderElectorRecord(uniqueName, instanceId, System.getProperty("user.name"));
                            int result = leaderElectionDAO.stealLeadership(uniqueName, currentLeader.getId(), expiryThreshold, newRecord);
                            if (result > 0) {
                                currentLeaderRecordId = newRecord.getId();
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
                
                long retryPeriod = getLeaderElectionConfiguration().getRetryPeriod().toMillis();
                long exp = (long) Math.pow(2, Math.min(consecutiveFailures - 1, 5));
                long backoffDelay = Math.min(retryPeriod * exp, MAX_BACKOFF_MILLIS);
                backoffUntil = System.currentTimeMillis() + backoffDelay;
            }
        }
    }
}
