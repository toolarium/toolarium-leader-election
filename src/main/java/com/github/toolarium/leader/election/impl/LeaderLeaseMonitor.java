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

    private final Set<DefaultLeaderLease> leases = ConcurrentHashMap.newKeySet();
    private ScheduledExecutorService executorService;


    /**
     * Start the monitor thread. Called once when the elector is initialized.
     * Keeping the thread alive for the elector's lifetime avoids per-task
     * create/destroy churn for sequential {@code runIfLeader} calls.
     */
    synchronized void start() {
        if (executorService == null) {
            executorService = Executors.newSingleThreadScheduledExecutor(runnable -> {
                Thread thread = new Thread(runnable, "leader-lease-monitor");
                thread.setDaemon(true);
                return thread;
            });
            executorService.scheduleWithFixedDelay(this::check, CHECK_PERIOD_MILLIS, CHECK_PERIOD_MILLIS, TimeUnit.MILLISECONDS);
        }
    }


    /**
     * Stop the monitor thread. Called when the elector is closed.
     */
    synchronized void stop() {
        if (executorService != null) {
            executorService.shutdown();
            executorService = null;
        }
    }


    /**
     * Monitor the lease of a running task.
     *
     * @param lease the lease
     */
    void register(DefaultLeaderLease lease) {
        leases.add(lease);
    }


    /**
     * Stop monitoring the lease of an ended task.
     *
     * @param lease the lease
     */
    void unregister(DefaultLeaderLease lease) {
        leases.remove(lease);
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
        } catch (RuntimeException e) {
            if (LOG.isDebugEnabled()) {
                LOG.debug("Could not check the leader leases: " + e.getMessage(), e);
            }
            LOG.info("Could not check the leader leases: {}, will retry on next check interval.", e.getMessage());
        }
    }
}
