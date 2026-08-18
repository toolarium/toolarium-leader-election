/*
 * DatabaseLeaderElectorImpl.java
 *
 * Copyright by toolarium, all rights reserved.
 */
package com.github.toolarium.leader.election.impl.database;

import com.github.toolarium.leader.election.LeaderElector;
import com.github.toolarium.leader.election.dto.DatabaseLeaderElectionConfiguration;
import com.github.toolarium.leader.election.dto.LeaderElectionInformation;
import com.github.toolarium.leader.election.exception.LeaderElectionException;
import com.github.toolarium.leader.election.impl.AbstractLeaderElectorImpl;
import com.github.toolarium.leader.election.impl.database.dao.LeaderElectionDAO;
import com.github.toolarium.leader.election.impl.database.dao.LeaderElectorRecord;
import java.sql.SQLException;
import java.time.Instant;
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
    private ScheduledExecutorService scheduledExecuterService;
    private ScheduledFuture<?> scheduledFuture;
    private LeaderElectionDAO leaderElectionDAO;
    private volatile String currentLeaderRecordId;
    private volatile int consecutiveFailures;


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
    }


    /**
     * @see com.github.toolarium.leader.election.impl.AbstractLeaderElectorImpl#initializeImplementation()
     */
    @Override
    protected void initializeImplementation() throws LeaderElectionException {

        try {
            leaderElectionDAO.init();

        } catch (SQLException e) {
            throw new LeaderElectionException("Could not prepare database for leader election: " + e.getMessage(), e);
        }

        scheduledExecuterService = Executors.newScheduledThreadPool(1);
        scheduledFuture = scheduledExecuterService.scheduleAtFixedRate(
                new DatabaseElectionHandler(), 0, getLeaderElectionConfiguration().getRetryPeriod().getSeconds(), TimeUnit.SECONDS);
    }


    /**
     * @see com.github.toolarium.leader.election.LeaderElector#close()
     */
    @Override
    public void close() {
        // Delete own leader record before closing
        if (currentLeaderRecordId != null) {
            try {
                leaderElectionDAO.deleteLeaderByNameAndId(
                        getLeaderElectionInformation().getUniqueName(), currentLeaderRecordId);
            } catch (Exception e) {
                LOG.warn("Could not delete leader record on close: " + e.getMessage(), e);
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
            String uniqueName = getLeaderElectionInformation().getUniqueName();
            String instanceId = getId();

            try {
                LeaderElectorRecord currentLeader = leaderElectionDAO.selectLeaderByName(uniqueName);

                if (currentLeader == null) {
                    // No leader exists, try to become one
                    LeaderElectorRecord newRecord = new LeaderElectorRecord(uniqueName, instanceId, System.getProperty("user.name"));
                    int result = leaderElectionDAO.insertLeader(newRecord);
                    if (result > 0) {
                        currentLeaderRecordId = newRecord.getId();
                        setLeader(true, null);
                    }
                } else if (instanceId.equals(currentLeader.getInstance())) {
                    // We are the leader, atomically renew the timestamp
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
                    // Another instance is the leader, check if the lease has expired
                    try {
                        Instant leaderTimestamp = Instant.parse(currentLeader.getTimestamp());
                        Instant expiry = leaderTimestamp.plus(getLeaderElectionConfiguration().getTimeout());
                        if (Instant.now().isAfter(expiry)) {
                            // Leader expired, try to steal leadership
                            leaderElectionDAO.deleteLeaderByNameAndId(uniqueName, currentLeader.getId());
                            LeaderElectorRecord newRecord = new LeaderElectorRecord(uniqueName, instanceId, System.getProperty("user.name"));
                            int result = leaderElectionDAO.insertLeader(newRecord);
                            if (result > 0) {
                                currentLeaderRecordId = newRecord.getId();
                                setLeader(true, null);
                            } else {
                                setLeader(false, null);
                            }
                        } else {
                            setLeader(false, null);
                        }
                    } catch (Exception e) {
                        setLeader(false, null);
                    }
                }
                consecutiveFailures = 0;
            } catch (Exception e) {
                consecutiveFailures++;
                if (consecutiveFailures >= CONSECUTIVE_FAILURE_THRESHOLD) {
                    LOG.error("Repeated failure (" + consecutiveFailures + ") verifying database cluster [" + uniqueName + "]: " + e.getMessage(), e);
                } else {
                    LOG.warn("Error occured while verify database cluster [" + uniqueName + "]: " + e.getMessage(), e);
                }
            }
        }
    }
}
