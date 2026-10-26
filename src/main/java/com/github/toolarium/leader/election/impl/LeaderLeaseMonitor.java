/*
 * LeaderLeaseMonitor.java
 *
 * Copyright by toolarium, all rights reserved.
 */
package com.github.toolarium.leader.election.impl;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


/**
 * Checks the leases of the running tasks and interrupts a task as soon as its lead is lost. The lead is lost locally
 * after the renew deadline, another instance can take over after the timeout: the check period is far below this
 * margin. The checks run on the scheduler shared by all electors ({@link LeaderElectionExecutors#getLeaseMonitorScheduler()})
 * and are only scheduled while tasks are running.
 *
 * @author patrick
 */
final class LeaderLeaseMonitor {
    private static final Logger LOG = LoggerFactory.getLogger(LeaderLeaseMonitor.class);
    private static final long CHECK_PERIOD_MILLIS = 100L;

    private final Set<DefaultLeaderLease> leases = ConcurrentHashMap.newKeySet();
    private boolean started;
    private ScheduledFuture<?> scheduledTask;
    // written by the shared check thread and by stop()
    private volatile int consecutiveCheckFailures;
    // true once the current run of failures was warned about: further failures are logged at debug level until a check succeeds
    private volatile boolean failureReported;


    /**
     * Start the monitor. Called once when the elector is initialized.
     * The periodic check task is only scheduled when at least one lease is registered
     * so the shared thread does not wake every 100ms when no {@code runIfLeader} tasks are active.
     */
    synchronized void start() {
        started = true;
    }


    /**
     * Stop the monitor. Called when the elector is closed. A check that is already running
     * completes; it only interrupts tasks whose lead is lost.
     */
    synchronized void stop() {
        started = false;
        // A last check right away: close() may not wait for the running tasks (called from one of them, or the close timeout
        // passed), and the periodic check is cancelled below. The calling thread is not interrupted: it is closing the elector.
        checkLeases(Thread.currentThread());
        if (scheduledTask != null) {
            scheduledTask.cancel(false);
            scheduledTask = null;
        }
        consecutiveCheckFailures = 0;
        failureReported = false;
    }


    /**
     * Monitor the lease of a running task. Starts the periodic check task on the
     * first registration so the shared thread does not poll when idle.
     *
     * @param lease the lease
     */
    synchronized void register(DefaultLeaderLease lease) {
        leases.add(lease);
        if (scheduledTask == null && started) {
            scheduledTask = LeaderElectionExecutors.getInstance().getLeaseMonitorScheduler().scheduleWithFixedDelay(this::check, CHECK_PERIOD_MILLIS, CHECK_PERIOD_MILLIS, TimeUnit.MILLISECONDS);
        }
    }


    /**
     * Stop monitoring the lease of an ended task. Cancels the periodic check task
     * when the last lease is removed so the shared thread does not poll when idle.
     *
     * @param lease the lease
     */
    synchronized void unregister(DefaultLeaderLease lease) {
        leases.remove(lease);
        if (leases.isEmpty() && scheduledTask != null) {
            scheduledTask.cancel(false);
            scheduledTask = null;
        }
    }


    /**
     * Interrupt tasks that have lost the lead.
     */
    private void check() {
        checkLeases(null);
    }


    /**
     * Interrupt tasks that have lost the lead.
     *
     * @param exclude a thread that is not interrupted, or null
     */
    private void checkLeases(Thread exclude) {
        try {
            for (DefaultLeaderLease lease : leases) {
                if (!lease.isTaskThread(exclude) && !lease.isValid()) {
                    lease.interruptIfRunning();
                }
            }
            if (failureReported) {
                failureReported = false;
                LOG.info("Checking the leader leases works again after {} failures.", consecutiveCheckFailures);
            }
            consecutiveCheckFailures = 0;
        } catch (Throwable e) {
            // the check does no I/O, so a failure is unexpected: warned once until a check succeeds again
            consecutiveCheckFailures++;
            if (LOG.isDebugEnabled()) {
                LOG.debug("Unexpected error checking the leader leases ({} times in a row): {}", consecutiveCheckFailures, e.toString(), e);
            }
            if (!failureReported) {
                failureReported = true;
                LOG.warn("Unexpected error checking the leader leases: {}. Tasks may not be interrupted when the lead is lost. Further errors "
                         + "are logged at debug level until a check succeeds. Please report it with the stack trace of the debug log.", e.toString());
            }
        }
    }
}
