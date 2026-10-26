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
    // Guarded by synchronized(this). Set to true inside the same lock that clears the interrupt
    // flag in runIfLeader(), preventing the monitor from sending a late interrupt after the task
    // has already finished and the thread has moved on to unrelated work.
    private boolean finished;


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
        this.finished = false;
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
     * Mark this lease as finished. Called inside {@code synchronized(this)} in
     * {@code runIfLeader()}'s finally block after the task has returned but before the
     * interrupt flag is inspected. Once marked finished, {@link #interruptIfRunning()}
     * becomes a no-op, preventing a late monitor interrupt from landing on the next
     * unrelated job running on the same thread.
     */
    synchronized void markFinished() {
        finished = true;
    }


    /**
     * Interrupt the task thread at most once.
     * Subsequent calls from the {@link LeaderLeaseMonitor} (every 100 ms) are ignored
     * after the first interrupt has been sent, preventing repeated interrupts during cleanup.
     * Also a no-op once {@link #markFinished()} has been called.
     */
    synchronized void interruptIfRunning() {
        if (finished) {
            return;
        }
        if (interruptedByMonitor.compareAndSet(false, true) && taskThread.isAlive()) {
            taskThread.interrupt();
        }
    }


    /**
     * Test if the task of this lease runs on the given thread.
     *
     * @param thread the thread or null
     * @return true if the task runs on the given thread
     */
    boolean isTaskThread(Thread thread) {
        return thread != null && taskThread == thread;
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
