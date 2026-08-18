/*
 * FileLockFactory.java
 *
 * Copyright by toolarium, all rights reserved.
 */
package com.github.toolarium.leader.election.impl.file;

import com.github.toolarium.leader.election.impl.file.exception.FileLockException;
import java.io.File;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


/**
 * The file lock factory.
 *  
 * @author patrick
 */
public final class FileLockFactory {
    private static final Logger LOG = LoggerFactory.getLogger(FileLockFactory.class);
    private Map<FileLock, Long> fileLockMap;
    private ScheduledExecutorService scheduledExecuterService;
    private ScheduledFuture<?> scheduledFuture;
    private int scheduleInterval = 1; 

    
    /**
     * Private class, the only instance of the singelton which will be created by accessing the holder class.
     *
     * @author patrick
     */
    private static final class HOLDER {
        static final FileLockFactory INSTANCE = new FileLockFactory();
    }

    
    /**
     * Constructor
     */
    private FileLockFactory() {
        fileLockMap = new ConcurrentHashMap<FileLock, Long>();
        startSchedule();

        Runtime.getRuntime().addShutdownHook(
                new Thread(FileLockFactory.class.getName() + ": Shutdown hook") { // add shutdown hook
                    /**
                     * @see java.lang.Thread#run()
                     */
                    @Override
                    public void run() {
                        shutdownSchedule();
                        cleanupFileLockMap();
                    }
                });
    }


    /**
     * Get the instance
     *
     * @return the instance
     */
    public static FileLockFactory getInstance() {
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
     * Get a file lock without timeout
     *
     * @param file the file to lock
     * @return return the file lock otherwise the {@link FileLockException} will be thrown. The file lock must be closed to free up resources.
     * @throws FileLockException In case the lock can't be aquired
     */
    public FileLock tryLock(final File file) throws FileLockException {
        return tryLock(file, null);
    }

    
    /**
     * Get a file lock with a timeout. After reaching the timeout the lock is not anymore valid.
     *
     * @param file the file to lock
     * @param lockPeriod the lock period. If it's null then the lock will be hold until restart of the jvm.
     * @return return the file lock otherwise the {@link FileLockException} will be thrown. The file lock must be closed to free up resources.
     */
    public FileLock tryLock(final File file, Duration lockPeriod) {
        return tryLock(file, lockPeriod, null);
    }

    
    /**
     * Get a file lock for a define period. After reaching the rewnew deadline the lock will be renewed. 
     *
     * @param file the file to lock
     * @param lockPeriod the lock period. If it's null then the lock will be hold until restart of the jvm.
     * @param renewDeadline the renew deadline where the lock period has to be renewed. If it's null then the lock will not be renewed after the lock period. Otherwise it tries to renew the lock after the lock period.
     * @return return the file lock otherwise the {@link FileLockException} will be thrown. The file lock must be closed to free up resources.
     */
    public FileLock tryLock(final File file, Duration lockPeriod, Duration renewDeadline) {
        FileLock fileLock = new FileLock(file, lockPeriod, renewDeadline);
        fileLockMap.put(fileLock, Thread.currentThread().threadId());

        try {
            fileLock.lock();
        } catch (FileLockException ex) {
            LOG.warn("Could not acquire file lock [" + fileLock.getId() + "] [" + fileLock.getFile() + "]: " + ex.getMessage());
        }
        return fileLock;
    }

    
    /**
     * Release a file lock
     *
     * @param fileLock the file lock
     */
    public void release(final FileLock fileLock) {
        if (fileLock == null || fileLock.getFile() == null) {
            return;
        }

        try {
            fileLock.close();
            fileLockMap.remove(fileLock);
        } catch (Exception e) {
            LOG.warn("File lock [" + fileLock.getId() + "]  [" + fileLock.getFile() + "] on " + fileLock.getLockTime() + " could not be removed: " + e.getMessage(), e);
        }
    }


    /**
     * The file lock handler
     * 
     * @author patrick
     */
    protected class FileLockHandler implements Runnable {
        /**
         * @see java.lang.Runnable#run()
         */
        public void run() {
            LOG.debug("Verify file locks...");
            try {
                for (FileLock fileLock : fileLockMap.keySet()) {
                    LOG.debug("Verify lock " + fileLock);
                    long now = Instant.now().toEpochMilli();
                    
                    long lockTime = 0;
                    if (fileLock.getLockTime() != null) {
                        lockTime = fileLock.getLockTime().toEpochMilli();
                    }
                    
                    if (fileLock.getRenewDeadline() != null) {
                        if (now - (lockTime + fileLock.getRenewDeadline().toMillis()) > 0) {
                            try {
                                LOG.debug("Try to renew file lock [" + fileLock.getId() + "] [" + fileLock.getFile() + "].");
                                fileLock.renew();
                            } catch (FileLockException e) {
                                LOG.debug("Losed file lock [" + fileLock.getId() + "] [" + fileLock.getFile() + "], could not be renewed: " + e.getMessage());
                            }
                        }
                    } else if (fileLock.getLockPeriod() != null && (now - (lockTime + fileLock.getLockPeriod().toMillis()) > 0)
                            || !ThreadUtil.getInstance().isThreadAlive(fileLock.getThreadId())) /* || !fileLock.hasLock() ) */ {
                        if (fileLock.hasLock()) {
                            LOG.debug("Losed file lock [" + fileLock.getId() + "] [" + fileLock.getFile() + "], timeout.");
                            release(fileLock);
                        }
                    } else {
                        // do nothing 
                    }
                }
            } catch (Exception e) {
                LOG.warn("Unexpeted exception: " + e.getMessage(), e);
            }
        }
    }

    
    /**
     * Start the schedule
     */
    private void startSchedule() {
        LOG.debug("Start scheduler with interval of " + scheduleInterval + " seconds.");
        scheduledExecuterService = Executors.newScheduledThreadPool(1);
        scheduledFuture = scheduledExecuterService.scheduleAtFixedRate(new FileLockHandler(), 0, scheduleInterval, TimeUnit.SECONDS);
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
     * Cleanup the file lock map
     */
    private void cleanupFileLockMap() {
        if (fileLockMap != null) {
            for (FileLock fileLock : fileLockMap.keySet()) {
                try {
                    if (fileLock.hasLock()) {
                        LOG.debug("Clean file lock [" + fileLock.getId() + "] [" + fileLock.getFile() + "] on " + fileLock.getLockTime() + ".");
                    }
                    fileLock.close();
                    
                } catch (Exception e) {
                    LOG.warn("File lock [" + fileLock.getId() + "] [" + fileLock.getFile() + "] on " + fileLock.getLockTime() + " could not be removed: " + e.getMessage(), e);
                }
            }
        }
    }
}
