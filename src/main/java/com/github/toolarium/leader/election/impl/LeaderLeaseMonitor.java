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
 * margin. One check for all electors of the JVM runs on the shared scheduler ({@link LeaderElectionExecutors#getLeaseMonitorScheduler()})
 * while tasks are running; it stops after a short idle time, so that short tasks run one after another do not schedule it
 * again each time.
 *
 * @author patrick
 */
final class LeaderLeaseMonitor {
    private static final Logger LOG = LoggerFactory.getLogger(LeaderLeaseMonitor.class);
    private static final long CHECK_PERIOD_MILLIS = 100L;
    // the shared check stops after this many checks without a running task (1 s)
    private static final int IDLE_CHECKS_BEFORE_STOP = 10;
    // the monitors with running tasks, checked by the shared check
    private static final Set<LeaderLeaseMonitor> ACTIVE_MONITORS = ConcurrentHashMap.newKeySet();
    private static final Object SHARED_CHECK_LOCK = new Object();
    // guarded by SHARED_CHECK_LOCK
    private static ScheduledFuture<?> sharedCheck;
    private static int idleChecks;
    // true while the shared check is scheduled: a task start then needs no global lock. Written under SHARED_CHECK_LOCK.
    private static volatile boolean sharedCheckRunning;

    private final Set<DefaultLeaderLease> leases = ConcurrentHashMap.newKeySet();
    private boolean started;
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
        if (!leases.isEmpty()) {
            activate();
        }
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
        ACTIVE_MONITORS.remove(this);
        consecutiveCheckFailures = 0;
        failureReported = false;
    }


    /**
     * Monitor the lease of a running task.
     *
     * @param lease the lease
     */
    synchronized void register(DefaultLeaderLease lease) {
        boolean first = leases.isEmpty();
        leases.add(lease);
        if (started && (first || !sharedCheckRunning)) {
            activate();
        }
    }


    /**
     * Stop monitoring the lease of an ended task.
     *
     * @param lease the lease
     */
    synchronized void unregister(DefaultLeaderLease lease) {
        leases.remove(lease);
        if (leases.isEmpty()) {
            ACTIVE_MONITORS.remove(this);
        }
    }


    /**
     * Add this monitor to the shared check and make sure the shared check runs.
     */
    private void activate() {
        // Added before the flag is read; the shared check clears the flag before it reads the monitors: either this sees the
        // check stopped and schedules it, or the check sees this monitor and schedules itself again.
        ACTIVE_MONITORS.add(this);
        if (!sharedCheckRunning) {
            scheduleSharedCheck();
        }
    }


    /**
     * Schedule the shared check if it is not scheduled.
     */
    private static void scheduleSharedCheck() {
        synchronized (SHARED_CHECK_LOCK) {
            idleChecks = 0;
            if (sharedCheck == null) {
                sharedCheck = LeaderElectionExecutors.getInstance().getLeaseMonitorScheduler().scheduleWithFixedDelay(LeaderLeaseMonitor::checkAll,
                        CHECK_PERIOD_MILLIS, CHECK_PERIOD_MILLIS, TimeUnit.MILLISECONDS);
            }
            sharedCheckRunning = true;
        }
    }


    /**
     * The shared check: interrupt the tasks of all electors that have lost the lead; stops after a short idle time.
     */
    private static void checkAll() {
        for (LeaderLeaseMonitor monitor : ACTIVE_MONITORS) {
            monitor.checkLeases(null);
        }
        synchronized (SHARED_CHECK_LOCK) {
            if (!ACTIVE_MONITORS.isEmpty()) {
                idleChecks = 0;
            } else if (++idleChecks >= IDLE_CHECKS_BEFORE_STOP && sharedCheck != null) {
                sharedCheckRunning = false;
                // a monitor activated meanwhile, which may not have seen the cleared flag: keep checking
                if (!ACTIVE_MONITORS.isEmpty()) {
                    sharedCheckRunning = true;
                    idleChecks = 0;
                    return;
                }
                sharedCheck.cancel(false);
                sharedCheck = null;
            }
        }
    }


    /**
     * Test if the shared check is scheduled.
     *
     * @return true if the shared check is scheduled
     */
    static boolean isSharedCheckScheduled() {
        synchronized (SHARED_CHECK_LOCK) {
            return sharedCheck != null;
        }
    }


    /**
     * Get the number of monitors with running tasks.
     *
     * @return the number of monitors with running tasks
     */
    static int getActiveMonitorCount() {
        return ACTIVE_MONITORS.size();
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
                         + "are logged at debug level until a check succeeds. Please report it, if possible with the stack trace (enable debug logging).", e.toString());
            }
        }
    }
}
