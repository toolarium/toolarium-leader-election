/*
 * DefaultLeaderLease.java
 *
 * Copyright by toolarium, all rights reserved.
 */
package com.github.toolarium.leader.election.impl;

import com.github.toolarium.leader.election.ILeaderLease;
import com.github.toolarium.leader.election.dto.ILeaderElectionConfiguration;
import com.github.toolarium.leader.election.dto.ILeaderElectionInformation;
import com.github.toolarium.leader.election.exception.LeaderElectionException;
import java.time.Instant;


/**
 * Default {@link LeaderLease} implementation held by a running {@link com.github.toolarium.leader.election.LeaderTask}.
 * The {@link LeaderLeaseMonitor} calls {@link #interruptIfRunning()} when {@link #isValid()} returns {@code false}.
 *
 * @author patrick
 */
final class DefaultLeaderLease implements ILeaderLease {
    private final AbstractLeaderElectorImpl<?> elector;
    private final ILeaderElectionInformation leaderElectionInformation;
    private final ILeaderElectionConfiguration leaderElectionConfiguration;
    private final Thread taskThread;


    /**
     * Constructor for DefaultLeaderLease
     *
     * @param elector the elector that owns this lease
     * @param leaderElectionInformation the leader election information
     * @param leaderElectionConfiguration the leader election configuration
     * @param taskThread the thread executing the task
     */
    DefaultLeaderLease(AbstractLeaderElectorImpl<?> elector,
                       ILeaderElectionInformation leaderElectionInformation,
                       ILeaderElectionConfiguration leaderElectionConfiguration,
                       Thread taskThread) {
        this.elector = elector;
        this.leaderElectionInformation = leaderElectionInformation;
        this.leaderElectionConfiguration = leaderElectionConfiguration;
        this.taskThread = taskThread;
    }


    /**
     * @see com.github.toolarium.leader.election.LeaderLease#getId()
     */
    @Override
    public String getId() {
        return elector.getId();
    }


    /**
     * @see com.github.toolarium.leader.election.LeaderLease#getLeaderElectionInformation()
     */
    @Override
    public ILeaderElectionInformation getLeaderElectionInformation() {
        return leaderElectionInformation;
    }


    /**
     * @see com.github.toolarium.leader.election.LeaderLease#getLeaderElectionConfiguration()
     */
    @Override
    public ILeaderElectionConfiguration getLeaderElectionConfiguration() {
        return leaderElectionConfiguration;
    }


    /**
     * @see com.github.toolarium.leader.election.LeaderLease#getLeaderSince()
     */
    @Override
    public Instant getLeaderSince() {
        return elector.getLeaderElectorTimestamp();
    }


    /**
     * @see com.github.toolarium.leader.election.LeaderLease#tryExtend()
     */
    @Override
    public boolean tryExtend() throws LeaderElectionException {
        return elector.extendLeaseNow();
    }


    /**
     * @see com.github.toolarium.leader.election.LeaderLease#isValid()
     */
    @Override
    public boolean isValid() {
        return elector.isLeader();
    }


    /**
     * Interrupt the task thread if it is still alive.
     * Called by the {@link LeaderLeaseMonitor} when the lease is no longer valid.
     */
    void interruptIfRunning() {
        if (taskThread.isAlive()) {
            taskThread.interrupt();
        }
    }
}
