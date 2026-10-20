/*
 * LeaderElectionScheduler.java
 *
 * Copyright by toolarium, all rights reserved.
 */
package com.github.toolarium.leader.election.impl;

import com.github.toolarium.leader.election.ILeaderElector;
import com.github.toolarium.leader.election.ILeaderElectorView;
import com.github.toolarium.leader.election.exception.LeaderElectionException;
import com.github.toolarium.leader.election.impl.database.DatabaseLeaderElectorImpl;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


/**
 * Tracks registered {@link ILeaderElector} instances and closes them on JVM shutdown.
 * Registration is performed by {@link com.github.toolarium.leader.election.LeaderElectionFactory}
 * for every elector it creates; the lifecycle of the elector is governed by the caller who
 * created it, not by this scheduler.
 *
 * @author patrick
 */
public final class LeaderElectionScheduler {
    private static final Logger LOG = LoggerFactory.getLogger(LeaderElectionScheduler.class);
    private final Set<ILeaderElector> leaderElectors;


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
    public void register(ILeaderElector leaderElector) {
        if (leaderElector != null) {
            leaderElectors.add(leaderElector);
        }
    }


    /**
     * Unregister a leader elector from this scheduler.
     *
     * @param leaderElector the leader elector to unregister
     */
    public void unregister(ILeaderElector leaderElector) {
        if (leaderElector != null) {
            leaderElectors.remove(leaderElector);
        }
    }


    /**
     * Returns a snapshot of all electors currently holding leadership.
     * For the DATABASE strategy, this includes leaders held by remote nodes in the cluster
     * (read directly from the database table). For all other strategies, only local
     * electors are included.
     *
     * @return unmodifiable collection of active leader views; never {@code null}
     */
    public Collection<ILeaderElectorView> getElectorViews() {
        List<ILeaderElectorView> result = new ArrayList<>();
        ILeaderElector[] snapshot = leaderElectors.toArray(new ILeaderElector[0]);

        for (ILeaderElector elector : snapshot) {
            if (elector instanceof DatabaseLeaderElectorImpl) {
                // DATABASE: the table is the authoritative state and already covers remote nodes.
                // Do NOT mix in the local isLeader() state — the DB view is the single source of truth.
                result.addAll(((DatabaseLeaderElectorImpl) elector).getDatabaseElectorViews());
            } else if (elector.isLeader() && elector instanceof ILeaderElectorView) {
                // FILE / NETWORK / KUBERNETES: use the local elector's known state.
                result.add((ILeaderElectorView) elector);
            }
        }

        return Collections.unmodifiableList(result);
    }


    /**
     * Forcefully releases the leadership lease held by the elector with the given id.
     * For a local elector the elector is closed immediately.
     * For a remote DATABASE leader the row is deleted from the database so another
     * node can take over.
     *
     * @param id the elector id to release (== {@link ILeaderElectorView#getId()})
     * @return {@code true} if found and released, {@code false} if not found
     * @throws LeaderElectionException if the elector cannot be closed cleanly
     */
    public boolean forceRelease(String id) throws LeaderElectionException {
        if (id == null) {
            return false;
        }
        ILeaderElector[] snapshot = leaderElectors.toArray(new ILeaderElector[0]);

        // First try local electors (any strategy).
        for (ILeaderElector elector : snapshot) {
            if (id.equals(elector.getId())) {
                LOG.info("Force-releasing leader elector [{}].", id);
                elector.close();
                return true;
            }
        }
        // For DATABASE strategy: attempt to delete the remote leader's DB row.
        for (ILeaderElector elector : snapshot) {
            if (elector instanceof DatabaseLeaderElectorImpl) {
                if (((DatabaseLeaderElectorImpl) elector).forceReleaseRemote(id)) {
                    return true;
                }
            }
        }
        return false;
    }


    /**
     * Close all registered leader electors on JVM shutdown.
     */
    private void cleanupLeaderElectors() {
        // Snapshot to avoid ConcurrentModificationException since close() calls unregister().
        for (ILeaderElector leaderElector : leaderElectors.toArray(new ILeaderElector[0])) {
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
