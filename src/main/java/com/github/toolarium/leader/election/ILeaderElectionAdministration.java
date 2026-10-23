/*
 * ILeaderElectionAdministration.java
 *
 * Copyright by toolarium, all rights reserved.
 */
package com.github.toolarium.leader.election;

import com.github.toolarium.leader.election.exception.LeaderElectionException;
import java.util.Collection;


/**
 * Administration API for the leader election framework.
 * Provides an overview of all electors currently holding leadership and the ability
 * to force-release a specific lease so that another candidate can take over immediately.
 *
 * <p>Obtain the singleton instance via {@link LeaderElectionFactory#getLeaderElectionAdministration()}.
 *
 * @author patrick
 */
public interface ILeaderElectionAdministration {

    /**
     * Returns a snapshot of all leader electors that currently hold leadership,
     * together with their identity and configuration settings.
     *
     * <p>For the DATABASE strategy this includes leaders held by <em>remote</em> nodes
     * in the cluster — the records are read directly from the shared database table.
     * For all other strategies only electors running in this JVM are returned.
     *
     * @return unmodifiable collection of active leader views; never {@code null}
     */
    Collection<ILeaderElectorView> getElectors();


    /**
     * Forcefully releases the leadership lease held by the elector with the given id.
     *
     * <p><b>Local elector:</b> the elector is closed immediately so that another candidate
     * can acquire the lease; the elector can be re-used by calling {@code elect()} again.
     *
     * <p><b>Remote DATABASE leader:</b> this method sets a
     * {@code force_release_deadline} timestamp in the database equal to
     * {@code dbNow + renewDeadline}. This has two effects:
     * <ol>
     *   <li>The current holder's renewal UPDATE is blocked (WHERE condition requires
     *       {@code force_release_deadline IS NULL}), so it steps down on its next tick.</li>
     *   <li>No other node can acquire leadership (via INSERT or steal) until the deadline
     *       elapses, guaranteeing that the old holder's 
     *       internal {@link com.github.toolarium.leader.election.ILeaderElector#isLeader()}
     *       monotonic check has also expired before a new leader is elected.</li>
     * </ol>
     * Together these two effects eliminate the split-brain window that would otherwise occur
     * with a plain row delete: the new leader acquires only after the old holder can no longer
     * return {@code true} from {@code isLeader()}.
     *
     * <p><b>Bound:</b> the old holder steps down within {@code retryPeriod} of the deadline
     * being written; the new leader acquires at most {@code renewDeadline} later.
     * Since {@code renewDeadline >= retryPeriod} (by configuration constraint), the new leader
     * always acquires after the old holder has stepped down — no overlap.
     *
     * <p><b>Access control:</b> this API is intentionally unrestricted at the framework level.
     * Any code in the JVM that holds a reference to the {@link ILeaderElectionAdministration}
     * singleton can release any elector, including electors from other subsystems.
     * Callers are responsible for ensuring that administrative operations are gated behind
     * appropriate application-level authorization (e.g. a secured management endpoint).
     *
     * @param id the {@link ILeaderElectorView#getId()} value of the elector to release
     * @return {@code true} if an elector with that id was found and released, {@code false} if not found
     * @throws LeaderElectionException if the elector cannot be closed cleanly
     */
    boolean forceRelease(String id) throws LeaderElectionException;
}
