/*
 * LeaderElectionScheduler.java
 *
 * Copyright by toolarium, all rights reserved.
 */
package com.github.toolarium.leader.election.impl;

import com.github.toolarium.leader.election.LeaderElector;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


/**
 * Tracks registered {@link LeaderElector} instances and closes them on JVM shutdown.
 * Registration is performed by {@link com.github.toolarium.leader.election.LeaderElectionFactory}
 * for every elector it creates; the lifecycle of the elector is governed by the caller who
 * created it, not by this scheduler.
 *
 * @author patrick
 */
public final class LeaderElectionScheduler {
    private static final Logger LOG = LoggerFactory.getLogger(LeaderElectionScheduler.class);
    private final Set<LeaderElector> leaderElectors;


    /**
     * Private class, the only instance of the singleton which will be created by accessing the holder class.
     *
     * @author patrick
     */
    private static final class HOLDER {
        static final LeaderElectionScheduler INSTANCE = new LeaderElectionScheduler();
    }


    /**
     * Constructor
     */
    private LeaderElectionScheduler() {
        leaderElectors = ConcurrentHashMap.newKeySet();

        Runtime.getRuntime().addShutdownHook(
                new Thread(LeaderElectionScheduler.class.getName() + ": Shutdown hook") {
                    /**
                     * @see java.lang.Thread#run()
                     */
                    @Override
                    public void run() {
                        cleanupLeaderElectors();
                    }
                });
    }


    /**
     * Get the instance
     *
     * @return the instance
     */
    public static LeaderElectionScheduler getInstance() {
        return HOLDER.INSTANCE;
    }


    /**
     * Register a leader elector so that it is closed on JVM shutdown.
     *
     * @param leaderElector the leader elector to register
     */
    public void register(LeaderElector leaderElector) {
        if (leaderElector != null) {
            leaderElectors.add(leaderElector);
        }
    }


    /**
     * Unregister a leader elector from this scheduler.
     *
     * @param leaderElector the leader elector to unregister
     */
    public void unregister(LeaderElector leaderElector) {
        if (leaderElector != null) {
            leaderElectors.remove(leaderElector);
        }
    }


    /**
     * Close all registered leader electors on JVM shutdown.
     */
    private void cleanupLeaderElectors() {
        // Snapshot to avoid ConcurrentModificationException since close() calls unregister().
        for (LeaderElector leaderElector : leaderElectors.toArray(new LeaderElector[0])) {
            try {
                if (leaderElector.isLeader()) {
                    if (LOG.isDebugEnabled()) {
                        LOG.debug("Clean leader elector [{}] on {}.", leaderElector.getId(), leaderElector.getLeaderElectorTimestamp());
                    }
                }
                leaderElector.close();
            } catch (Exception e) {
                LOG.warn("Leader elector [{}] could not be closed on shutdown: {}", leaderElector.getId(), e.getMessage());
                if (LOG.isDebugEnabled()) {
                    LOG.debug("Error detail:", e);
                }
            }
        }
    }
}
