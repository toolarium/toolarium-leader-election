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
import java.util.concurrent.atomic.AtomicBoolean;


/**
 * Default {@link LeaderLease} implementation held by a running {@link com.github.toolarium.leader.election.ILeaderTask}.
 * The {@link LeaderLeaseMonitor} calls {@link #interruptIfRunning()} when {@link #isValid()} returns {@code false}.
 *
 * @author patrick
 */
final class DefaultLeaderLease implements ILeaderLease {
    private final AbstractLeaderElectorImpl<?> elector;
    private final ILeaderElectionInformation leaderElectionInformation;
    private final ILeaderElectionConfiguration leaderElectionConfiguration;
    private final Thread taskThread;
    private final long capturedTerm;
    private final AtomicBoolean interruptedByMonitor;


    /**
     * Constructor for DefaultLeaderLease
     *
     * @param elector the elector that owns this lease
     * @param leaderElectionInformation the leader election information
     * @param leaderElectionConfiguration the leader election configuration
     * @param taskThread the thread executing the task
     * @param capturedTerm the leadership term at the moment the task started
     */
    DefaultLeaderLease(AbstractLeaderElectorImpl<?> elector,
                       ILeaderElectionInformation leaderElectionInformation,
                       ILeaderElectionConfiguration leaderElectionConfiguration,
                       Thread taskThread,
                       long capturedTerm) {
        this.elector = elector;
        this.leaderElectionInformation = leaderElectionInformation;
        this.leaderElectionConfiguration = leaderElectionConfiguration;
        this.taskThread = taskThread;
        this.capturedTerm = capturedTerm;
        this.interruptedByMonitor = new AtomicBoolean(false);
    }


    /**
     * @see com.github.toolarium.leader.election.ILeaderLease#getId()
     */
    @Override
    public String getId() {
        return elector.getId();
    }


    /**
     * @see com.github.toolarium.leader.election.ILeaderLease#getLeaderElectionInformation()
     */
    @Override
    public ILeaderElectionInformation getLeaderElectionInformation() {
        return leaderElectionInformation;
    }


    /**
     * @see com.github.toolarium.leader.election.ILeaderLease#getLeaderElectionConfiguration()
     */
    @Override
    public ILeaderElectionConfiguration getLeaderElectionConfiguration() {
        return leaderElectionConfiguration;
    }


    /**
     * @see com.github.toolarium.leader.election.ILeaderLease#getLeaderSince()
     */
    @Override
    public Instant getLeaderSince() {
        return elector.getLeaderElectorTimestamp();
    }


    /**
     * @see com.github.toolarium.leader.election.ILeaderLease#tryExtend()
     */
    @Override
    public boolean tryExtend() throws LeaderElectionException {
        return elector.extendLease();
    }


    /**
     * Returns {@code true} only when this node is still the leader AND it is still the same
     * leadership term in which the lease was created. Once invalid the lease will never become
     * valid again, even if the elector re-acquires leadership in a later term.
     *
     * @see com.github.toolarium.leader.election.ILeaderLease#isValid()
     */
    @Override
    public boolean isValid() {
        return elector.isLeader() && elector.getLeadershipTerm() == capturedTerm;
    }


    /**
     * Interrupt the task thread at most once.
     * Subsequent calls from the {@link LeaderLeaseMonitor} (every 100 ms) are ignored
     * after the first interrupt has been sent, preventing repeated interrupts during cleanup.
     */
    synchronized void interruptIfRunning() {
        if (interruptedByMonitor.compareAndSet(false, true) && taskThread.isAlive()) {
            taskThread.interrupt();
        }
    }


    /**
     * Returns {@code true} if the lease monitor has already sent an interrupt to the task thread.
     *
     * @return true if the monitor has already interrupted the task thread
     */
    boolean wasInterruptedByMonitor() {
        return interruptedByMonitor.get();
    }
}
