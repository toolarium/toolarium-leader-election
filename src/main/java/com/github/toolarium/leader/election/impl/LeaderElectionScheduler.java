/*
 * LeaderElectionScheduler.java
 *
 * Copyright by toolarium, all rights reserved.
 */
package com.github.toolarium.leader.election.impl;

import com.github.toolarium.leader.election.LeaderElector;
import java.lang.ref.WeakReference;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


/**
 * The leader election scheduler
 * 
 * @author patrick
 */
public final class LeaderElectionScheduler {
    private static final Logger LOG = LoggerFactory.getLogger(LeaderElectionScheduler.class);
    private ScheduledExecutorService scheduledExecuterService;
    private ScheduledFuture<?> scheduledFuture;
    private int scheduleInterval = 1; 
    private Map<LeaderElector, WeakReference<Thread>> leaderElectorMap;

    
    /**
     * Private class, the only instance of the singelton which will be created by accessing the holder class.
     *
     * @author patrick
     */
    private static final class HOLDER {
        static final LeaderElectionScheduler INSTANCE = new LeaderElectionScheduler();
    }

    
    /**
     * Constructor
     */
    private LeaderElectionScheduler() {
        leaderElectorMap = new ConcurrentHashMap<>();
        startSchedule();
        
        Runtime.getRuntime().addShutdownHook(
                new Thread(LeaderElectionScheduler.class.getName() + ": Shutdown hook") { // add shutdown hook
                    /**
                     * @see java.lang.Thread#run()
                     */
                    @Override
                    public void run() {
                        shutdownSchedule();
                        cleanupLeaderElectorMap();
                    }
                });
    }

    
    /**
     * Get the instance
     *
     * @return the instance
     */
    public static LeaderElectionScheduler getInstance() {
        return HOLDER.INSTANCE;
    }

    
    /**
     * Set the schedule interval
     *
     * @param seconds the schedule interval in seconds
     */
    public void setScheduleInterval(int seconds) {
        if (scheduleInterval != seconds) {
            this.scheduleInterval = seconds;
            shutdownSchedule();
            startSchedule();
        }
    }
    
    
    /**
     * Register a leader elector to be managed by this scheduler.
     * The scheduler will close the elector automatically if its creator thread is no longer alive.
     *
     * @param leaderElector the leader elector to register
     */
    public void register(LeaderElector leaderElector) {
        if (leaderElector != null) {
            leaderElectorMap.put(leaderElector, new WeakReference<>(Thread.currentThread()));
        }
    }


    /**
     * Unregister a leader elector from this scheduler.
     *
     * @param leaderElector the leader elector to unregister
     */
    public void unregister(LeaderElector leaderElector) {
        if (leaderElector != null) {
            leaderElectorMap.remove(leaderElector);
        }
    }


    /**
     * The leader election handler
     *
     * @author patrick
     */
    protected class LeaderElectionHandler implements Runnable {
        /**
         * @see java.lang.Runnable#run()
         */
        public void run() {
            LOG.debug("Verify leader elector registrations...");
            try {
                for (Map.Entry<LeaderElector, WeakReference<Thread>> entry : leaderElectorMap.entrySet()) {
                    if (!isThreadAlive(entry.getValue())) {
                        LeaderElector leaderElector = entry.getKey();
                        LOG.debug("Creator thread is no longer alive, closing leader elector [" + leaderElector.getId() + "].");
                        // Unregister before close() so the elector is removed from the map even if close() throws before reaching its own unregister() call in super.close().
                        unregister(leaderElector);
                        try {
                            leaderElector.close();
                        } catch (Exception e) {
                            LOG.warn("Could not close leader elector [" + leaderElector.getId() + "]: " + e.getMessage(), e);
                        }
                    }
                }
            } catch (Exception e) {
                LOG.warn("Unexpected exception: " + e.getMessage(), e);
            }
        }
    }


    /**
     * Check if a thread with the given id is still alive.
     *
     * @param threadId the thread id
     * @return true if it is alive
     */
    private boolean isThreadAlive(WeakReference<Thread> ref) {
        if (ref == null) {
            return false;
        }
        Thread t = ref.get();
        return t != null && t.isAlive();
    }
    
    
    /**
     * Start the schedule
     */
    private void startSchedule() {
        LOG.debug("Start scheduler with interval of " + scheduleInterval + " seconds.");
        scheduledExecuterService = Executors.newScheduledThreadPool(1);
        scheduledFuture = scheduledExecuterService.scheduleAtFixedRate(new LeaderElectionHandler(), 0, scheduleInterval, TimeUnit.SECONDS);
    }

    
    /**
     * Stop the schdule
     */
    private void shutdownSchedule() {
        if (scheduledFuture != null) {
            try {
                scheduledFuture.cancel(true);
            } catch (Exception e) {
                // NOP
            }
            scheduledFuture = null;
        }

        if (scheduledExecuterService != null) {
            try {
                scheduledExecuterService.shutdown();
                
                try {
                    scheduledExecuterService.awaitTermination(scheduleInterval, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    //
                }
            } catch (Exception e) {
                // NOP
            }
            scheduledExecuterService = null;
        }
        
        LOG.debug("Stopped the scheduler.");
    }


    /**
     * Cleanup the leader elector map
     */
    private void cleanupLeaderElectorMap() {
        if (leaderElectorMap != null) {
            // Snapshot keys to avoid ConcurrentModificationException since close() calls unregister() which modifies the map.
            for (LeaderElector leaderElector : leaderElectorMap.keySet().toArray(new LeaderElector[0])) {
                try {
                    if (leaderElector.isLeader()) {
                        LOG.debug("Clean leader elector [" + leaderElector.getId() + "] on " + leaderElector.getLeaderElectorTimestamp() + ".");
                    }
                    leaderElector.close();
                } catch (Exception e) {
                    LOG.warn("Leader elector [" + leaderElector.getId() + "] on " + leaderElector.getLeaderElectorTimestamp() + " could not be removed: " + e.getMessage(), e);
                }
            }
        }
    }
}
