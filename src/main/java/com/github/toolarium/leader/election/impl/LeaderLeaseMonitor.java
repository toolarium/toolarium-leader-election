/*
 * LeaderLeaseMonitor.java
 *
 * Copyright by toolarium, all rights reserved.
 */
package com.github.toolarium.leader.election.impl;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


/**
 * Checks the leases of the running tasks and interrupts a task as soon as its lead is lost. The lead is lost locally
 * after the renew deadline, another instance can take over after the timeout: the check period is far below this
 * margin. The daemon thread only runs while tasks are running.
 *
 * @author patrick
 */
final class LeaderLeaseMonitor {
    private static final Logger LOG = LoggerFactory.getLogger(LeaderLeaseMonitor.class);
    private static final long CHECK_PERIOD_MILLIS = 100L;
    private static final int CONSECUTIVE_FAILURE_WARN_THRESHOLD = 3;

    private final Set<DefaultLeaderLease> leases = ConcurrentHashMap.newKeySet();
    private ScheduledExecutorService executorService;
    private ScheduledFuture<?> scheduledTask;
    private int consecutiveCheckFailures;


    /**
     * Start the executor thread. Called once when the elector is initialized.
     * The periodic check task is only scheduled when at least one lease is registered
     * so the thread does not wake every 100ms when no {@code runIfLeader} tasks are active.
     */
    synchronized void start() {
        if (executorService == null) {
            executorService = Executors.newSingleThreadScheduledExecutor(runnable -> {
                Thread thread = new Thread(runnable, "leader-lease-monitor");
                thread.setDaemon(true);
                return thread;
            });
        }
    }


    /**
     * Stop the monitor thread. Called when the elector is closed.
     */
    synchronized void stop() {
        if (scheduledTask != null) {
            scheduledTask.cancel(false);
            scheduledTask = null;
        }
        if (executorService != null) {
            executorService.shutdown();
            try {
                executorService.awaitTermination(CHECK_PERIOD_MILLIS * 2, TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            executorService = null;
            consecutiveCheckFailures = 0;
        }
    }


    /**
     * Monitor the lease of a running task. Starts the periodic check task on the
     * first registration so the executor thread does not poll when idle.
     *
     * @param lease the lease
     */
    synchronized void register(DefaultLeaderLease lease) {
        leases.add(lease);
        if (scheduledTask == null && executorService != null) {
            scheduledTask = executorService.scheduleWithFixedDelay(this::check, CHECK_PERIOD_MILLIS, CHECK_PERIOD_MILLIS, TimeUnit.MILLISECONDS);
        }
    }


    /**
     * Stop monitoring the lease of an ended task. Cancels the periodic check task
     * when the last lease is removed so the executor thread does not poll when idle.
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
        try {
            for (DefaultLeaderLease lease : leases) {
                if (!lease.isValid()) {
                    lease.interruptIfRunning();
                }
            }
            consecutiveCheckFailures = 0;
        } catch (Throwable e) {
            consecutiveCheckFailures++;
            if (e instanceof Error) {
                LOG.error("Fatal error checking leader leases: {}, tasks may not be interrupted on leadership loss.", e.getMessage());
                if (LOG.isDebugEnabled()) {
                    LOG.debug("Error detail:", e);
                }
            } else if (consecutiveCheckFailures >= CONSECUTIVE_FAILURE_WARN_THRESHOLD) {
                if (LOG.isDebugEnabled()) {
                    LOG.debug("Could not check the leader leases: {}", e.getMessage(), e);
                }
                LOG.warn("Repeated failure ({}) checking leader leases: {}, tasks may not be interrupted promptly on leadership loss.", consecutiveCheckFailures, e.getMessage());
            } else {
                if (LOG.isDebugEnabled()) {
                    LOG.debug("Could not check the leader leases: {}", e.getMessage(), e);
                }
                LOG.info("Could not check the leader leases: {}, will retry on next check interval.", e.getMessage());
            }
        }
    }
}
