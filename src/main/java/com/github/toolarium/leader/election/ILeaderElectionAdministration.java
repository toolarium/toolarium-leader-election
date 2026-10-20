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
     * For a local elector the elector is closed immediately so that another candidate
     * can acquire the lease; the elector can be re-used by calling {@code elect()} again.
     * For a remote DATABASE leader the row is deleted from the shared database table so
     * that another node can take over immediately.
     *
     * @param id the {@link ILeaderElectorView#getId()} value of the elector to release
     * @return {@code true} if an elector with that id was found and released, {@code false} if not found
     * @throws LeaderElectionException if the elector cannot be closed cleanly
     */
    boolean forceRelease(String id) throws LeaderElectionException;
}
