/*
 * LeaderElectorSnapshot.java
 *
 * Copyright by toolarium, all rights reserved.
 */
package com.github.toolarium.leader.election.impl.dto;

import com.github.toolarium.leader.election.ILeaderElectorView;
import com.github.toolarium.leader.election.dto.ILeaderElectionConfiguration;
import com.github.toolarium.leader.election.dto.ILeaderElectionInformation;
import com.github.toolarium.leader.election.dto.LeaderElectionInformation;
import java.time.Duration;
import java.time.Instant;


/**
 * An immutable snapshot of a local leader elector for the administration API. Unlike the elector itself, it cannot be
 * cast back to the elector to close it or to run tasks under its lead, and it does not keep the elector reachable.
 *
 * @author patrick
 */
public final class LeaderElectorSnapshot implements ILeaderElectorView {
    private final String id;
    private final ILeaderElectionInformation leaderElectionInformation;
    private final ILeaderElectionConfiguration leaderElectionConfiguration;
    private final Instant leaderElectorTimestamp;
    private final Duration remainingLeadTime;
    private final long capturedNanos;


    /**
     * Constructor for LeaderElectorSnapshot
     *
     * @param view the view of the elector to take the snapshot of
     */
    public LeaderElectorSnapshot(ILeaderElectorView view) {
        this.capturedNanos = System.nanoTime();
        this.id = view.getId();
        this.leaderElectionInformation = copy(view.getLeaderElectionInformation());
        // a copy of the timings only: the configuration of a strategy may hold e.g. an encryption key or the DataSource
        this.leaderElectionConfiguration = new LeaderElectionConfigurationSnapshot(view.getLeaderElectionConfiguration());
        this.leaderElectorTimestamp = view.getLeaderElectorTimestamp();
        this.remainingLeadTime = view.getRemainingLeadTime();
    }


    /**
     * @see com.github.toolarium.leader.election.ILeaderElectorView#getId()
     */
    @Override
    public String getId() {
        return id;
    }


    /**
     * @see com.github.toolarium.leader.election.ILeaderElectorView#getLeaderElectionInformation()
     */
    @Override
    public ILeaderElectionInformation getLeaderElectionInformation() {
        return leaderElectionInformation;
    }


    /**
     * @see com.github.toolarium.leader.election.ILeaderElectorView#getLeaderElectionConfiguration()
     */
    @Override
    public ILeaderElectionConfiguration getLeaderElectionConfiguration() {
        return leaderElectionConfiguration;
    }


    /**
     * @see com.github.toolarium.leader.election.ILeaderElectorView#getLeaderElectorTimestamp()
     */
    @Override
    public Instant getLeaderElectorTimestamp() {
        return leaderElectorTimestamp;
    }


    /**
     * @see com.github.toolarium.leader.election.ILeaderElectorView#getRemainingLeadTime()
     *     The remaining lead time at the snapshot, less the time passed since.
     */
    @Override
    public Duration getRemainingLeadTime() {
        return remainingLeadTime.minusNanos(System.nanoTime() - capturedNanos);
    }


    /**
     * @see java.lang.Object#toString()
     */
    @Override
    public String toString() {
        return "LeaderElectorSnapshot [id=" + id + ", leaderElectionInformation=" + leaderElectionInformation + ", leaderElectorTimestamp=" + leaderElectorTimestamp + "]";
    }


    /**
     * Copy the leader election information, so that the snapshot does not share the mutable information of the elector.
     *
     * @param information the information
     * @return the copy
     */
    private static ILeaderElectionInformation copy(ILeaderElectionInformation information) {
        if (information == null) {
            return null;
        }
        LeaderElectionInformation copy = new LeaderElectionInformation(information.getNamespace(), information.getName(), information.getIdentity());
        copy.setUsername(information.getUsername());
        return copy;
    }
}
