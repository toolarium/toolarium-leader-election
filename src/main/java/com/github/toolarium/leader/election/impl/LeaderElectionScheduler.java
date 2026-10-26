/*
 * LeaderElectionScheduler.java
 *
 * Copyright by toolarium, all rights reserved.
 */
package com.github.toolarium.leader.election.impl;

import com.github.toolarium.leader.election.ILeaderElector;
import com.github.toolarium.leader.election.ILeaderElectorView;
import com.github.toolarium.leader.election.dto.db.DatabaseLeaderElectionConfiguration;
import com.github.toolarium.leader.election.exception.LeaderElectionException;
import com.github.toolarium.leader.election.impl.database.DatabaseLeaderElectorImpl;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;
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
    // Hard cap on the total time spent in cleanupLeaderElectors() during JVM shutdown.
    // Each elector's close() already has its own closeTimeout; with many electors registered
    // the sequential sum can exceed the JVM's implicit shutdown-hook time budget, causing the
    // JVM to kill the hook thread before all rows are deleted (RES-4).
    private static final long SHUTDOWN_CLEANUP_TIMEOUT_SECONDS = 30L;
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
        // Several DATABASE electors (different election groups) usually share one table, and every
        // one of them returns all rows of it: query each DataSource/table only once and drop
        // duplicate rows (same election group and elector instance).
        Map<DataSource, Set<String>> queriedTables = new IdentityHashMap<>();
        Set<String> seenRows = new HashSet<>();

        for (ILeaderElector elector : snapshot) {
            if (elector instanceof DatabaseLeaderElectorImpl) {
                // DATABASE: the table is the authoritative state and already covers remote nodes.
                // Do NOT mix in the local isLeader() state; the DB view is the single source of truth.
                DatabaseLeaderElectorImpl dbElector = (DatabaseLeaderElectorImpl) elector;
                if (isTableAlreadyQueried(dbElector, queriedTables)) {
                    continue;
                }
                for (ILeaderElectorView view : dbElector.getDatabaseElectorViews()) {
                    if (seenRows.add(view.getLeaderElectionInformation().getElectionGroupName() + "|" + view.getId())) {
                        result.add(view);
                    }
                }
            } else if (elector.isLeader() && elector instanceof ILeaderElectorView) {
                // FILE / NETWORK / KUBERNETES: use the local elector's known state.
                result.add((ILeaderElectorView) elector);
            }
        }

        return Collections.unmodifiableList(result);
    }


    /**
     * Check whether the table of a DATABASE elector was already queried (same DataSource instance and
     * table name return the same rows) and record it otherwise.
     *
     * @param dbElector the database elector
     * @param queriedTables the tables queried so far, per DataSource instance
     * @return true if the table was already queried; false if it must be queried (also when it
     *     cannot be determined)
     */
    private static boolean isTableAlreadyQueried(DatabaseLeaderElectorImpl dbElector, Map<DataSource, Set<String>> queriedTables) {
        DatabaseLeaderElectionConfiguration config = dbElector.getLeaderElectionConfiguration();
        if (config == null || config.getDataSource() == null || config.getLeaderElectionDatabaseStructure() == null) {
            return false;
        }
        String tableName = config.getLeaderElectionDatabaseStructure().getTableName();
        return !queriedTables.computeIfAbsent(config.getDataSource(), ds -> new HashSet<>()).add(tableName);
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
        if (id == null || id.isBlank()) {
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
     * A hard deadline of {@value #SHUTDOWN_CLEANUP_TIMEOUT_SECONDS} seconds guards the total
     * cleanup time: sequential close() calls each block for up to their own closeTimeout, so
     * without a cap a large number of registered electors can exceed the JVM's implicit
     * shutdown-hook time budget and cause the hook thread to be killed prematurely.
     */
    private void cleanupLeaderElectors() {
        // Snapshot to avoid ConcurrentModificationException since close() calls unregister().
        ILeaderElector[] snapshot = leaderElectors.toArray(new ILeaderElector[0]);
        long deadlineNanos = System.nanoTime() + TimeUnit.SECONDS.toNanos(SHUTDOWN_CLEANUP_TIMEOUT_SECONDS);
        for (ILeaderElector leaderElector : snapshot) {
            if (System.nanoTime() > deadlineNanos) {
                LOG.info("Shutdown cleanup deadline reached; remaining leader elector(s) will expire naturally after the configured timeout.");
                break;
            }
            try {
                if (leaderElector.isLeader()) {
                    if (LOG.isDebugEnabled()) {
                        LOG.debug("Clean leader elector [{}] on {}.", leaderElector.getId(), leaderElector.getLeaderElectorTimestamp());
                    }
                }
                leaderElector.close();
            } catch (Exception e) {
                LOG.info("Leader elector [{}] could not be closed on shutdown: {}, no action required, JVM is exiting.", leaderElector.getId(), e.getMessage());
                if (LOG.isDebugEnabled()) {
                    LOG.debug("Error detail:", e);
                }
            }
        }
    }
}
