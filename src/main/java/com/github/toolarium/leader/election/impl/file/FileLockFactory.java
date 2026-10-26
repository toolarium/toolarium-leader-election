/*
 * FileLockFactory.java
 *
 * Copyright by toolarium, all rights reserved.
 */
package com.github.toolarium.leader.election.impl.file;

import com.github.toolarium.leader.election.impl.LeaderElectionExecutors;
import com.github.toolarium.leader.election.impl.file.exception.FileLockException;
import java.io.File;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
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
    private static final String LOG_SEP = "] [";
    private static final String HANDLER_THREAD_NAME = "leader-election-file-lock";
    private static final String RENEW_THREAD_NAME = "leader-election-file-renew";
    private Map<FileLock, Long> fileLockMap;
    // the renewal that runs per lock, so that a lock is not renewed twice at the same time
    private final Map<FileLock, Future<?>> runningRenewals = new ConcurrentHashMap<>();
    // The lock handler runs on the shared executors only while file locks exist. Guarded by this factory's monitor.
    private LeaderElectionExecutors.PeriodicTask handlerTask;
    private int scheduleInterval = 1;
    private boolean shutdown;

    
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
        handlerTask = null;
        shutdown = false;

        Runtime.getRuntime().addShutdownHook(
                new Thread(FileLockFactory.class.getName() + ": Shutdown hook") { // add shutdown hook
                    /**
                     * @see java.lang.Thread#run()
                     */
                    @Override
                    public void run() {
                        // The locks are not released here: shutdown hooks run concurrently, so leader tasks may still run
                        // while the electors are closed by their own hook, which releases each lock once its tasks ended.
                        // The operating system releases all remaining locks when the process exits.
                        shutdownSchedule();
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
    public synchronized void setScheduleInterval(int seconds) {
        if (scheduleInterval != seconds) {
            this.scheduleInterval = seconds;
            if (handlerTask != null) {
                handlerTask.cancel();
                handlerTask = null;
                scheduleIfNeeded();
            }
        }
    }


    /**
     * Test if the lock handler is scheduled. It only runs while file locks exist.
     *
     * @return true if the lock handler is scheduled
     */
    synchronized boolean isScheduled() {
        return handlerTask != null;
    }


    /**
     * Get the number of file locks of this factory.
     *
     * @return the number of file locks
     */
    int getLockCount() {
        return fileLockMap.size();
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
        return tryLock(file, lockPeriod, renewDeadline, null);
    }


    /**
     * Get a file lock that is renewed, and tried again after the retry period while another process holds it.
     *
     * @param file the file to lock
     * @param lockPeriod the lock period. If it's null then the lock will be hold until restart of the jvm.
     * @param renewDeadline the renew deadline where the lock period has to be renewed. If it's null then the lock will not be renewed after the lock period.
     * @param retryPeriod the period after which a lock that is not held is tried again, or null to try on every check
     * @return return the file lock. The file lock must be closed to free up resources.
     */
    public FileLock tryLock(final File file, Duration lockPeriod, Duration renewDeadline, Duration retryPeriod) {
        FileLock fileLock = new FileLock(file, lockPeriod, renewDeadline, retryPeriod);
        try {
            fileLock.lock();
        } catch (FileLockException ex) {
            LOG.info("Could not acquire file lock [{}] [{}]: {}, will retry on next election tick.", fileLock.getId(), fileLock.getFile(), ex.getMessage());
        }

        // registered after the first attempt: the lock handler runs at once and must not compete with that attempt
        fileLockMap.put(fileLock, ThreadUtil.getInstance().getThreadId());
        scheduleIfNeeded();
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

        fileLockMap.remove(fileLock);
        runningRenewals.remove(fileLock);
        cancelScheduleIfIdle();
        try {
            fileLock.close();
        } catch (Exception e) {
            if (LOG.isDebugEnabled()) {
                LOG.debug("File lock [" + fileLock.getId() + "]  [" + fileLock.getFile() + "] on " + fileLock.getLockTime() + " could not be removed: " + e.getMessage(), e);
            }
            LOG.info("File lock [{}] [{}] on {} could not be removed: {}, lock will expire automatically.", fileLock.getId(), fileLock.getFile(), fileLock.getLockTime(), e.getMessage());
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
            if (LOG.isDebugEnabled()) {
                LOG.debug("Verify file locks...");
            }
            try {
                for (FileLock fileLock : fileLockMap.keySet()) {
                    if (fileLock.isClosed()) {
                        // closed by its owner without release(): nothing to renew anymore
                        fileLockMap.remove(fileLock);
                        runningRenewals.remove(fileLock);
                        cancelScheduleIfIdle();
                        continue;
                    }
                    if (LOG.isDebugEnabled()) {
                        LOG.debug("Verify lock " + fileLock);
                    }
                    long now = Instant.now().toEpochMilli();

                    // Capture getLockTime() once: it is a volatile field that unlock() sets to
                    // null concurrently, so a double-read (null-check then .toEpochMilli()) would
                    // produce a NullPointerException if the value changes between the two reads.
                    Instant lockInstant = fileLock.getLockTime();
                    long lockTime = 0;
                    if (lockInstant != null) {
                        lockTime = lockInstant.toEpochMilli();
                    }
                    
                    // read once: close() clears the fields concurrently
                    Duration renewDeadline = fileLock.getRenewDeadline();
                    Duration lockPeriod = fileLock.getLockPeriod();
                    if (renewDeadline != null) {
                        if (lockInstant == null && !fileLock.isRetryDue()) {
                            // not held: try again only after the retry period
                            continue;
                        }
                        // A held lock is renewed in place one check interval before half the renew deadline, so that the gap between two
                        // renewals stays well below the renew deadline (the lead is fenced on it). Measured with the monotonic clock: a
                        // step of the wall clock must neither stop nor force the renewals.
                        long renewAfterNanos = Math.max(0L, renewDeadline.toNanos() / 2 - TimeUnit.SECONDS.toNanos(scheduleInterval));
                        if (lockInstant == null || fileLock.getLockAgeNanos() > renewAfterNanos) {
                            renewAsync(fileLock);
                        }
                    } else if (lockPeriod != null && (now - (lockTime + lockPeriod.toMillis()) > 0)
                            || !fileLock.isOwnerThreadAlive()) {
                        if (fileLock.hasLock()) {
                            if (LOG.isDebugEnabled()) {
                                LOG.debug("Losed file lock [" + fileLock.getId() + LOG_SEP + fileLock.getFile() + "], timeout.");
                            }
                            release(fileLock);
                        }
                    } else {
                        // do nothing 
                    }
                }
            } catch (Throwable e) {
                // also an Error: the handler must keep running, otherwise no lock is renewed or tried again anymore
                if (LOG.isDebugEnabled()) {
                    LOG.debug("Unexpected exception: " + e.getMessage(), e);
                }
                LOG.info("Unexpected exception during file lock check: {}, no action required, will retry on next check.", e.toString());
            }
        }
    }

    
    /**
     * Renew or acquire a lock on its own task of the shared executors: a lock on a slow or stalled (network) file system must
     * not delay the renewals of the other locks. A lock whose previous renewal still runs is skipped.
     *
     * @param fileLock the lock
     */
    private void renewAsync(FileLock fileLock) {
        Future<?> running = runningRenewals.get(fileLock);
        if (running != null && !running.isDone()) {
            if (LOG.isDebugEnabled()) {
                LOG.debug("The previous renewal of file lock [" + fileLock.getId() + LOG_SEP + fileLock.getFile() + "] still runs.");
            }
            return;
        }
        try {
            runningRenewals.put(fileLock, LeaderElectionExecutors.getInstance().submit(RENEW_THREAD_NAME, () -> {
                try {
                    if (LOG.isDebugEnabled()) {
                        LOG.debug("Try to renew file lock [" + fileLock.getId() + LOG_SEP + fileLock.getFile() + "].");
                    }
                    fileLock.renew();
                } catch (FileLockException e) {
                    if (LOG.isDebugEnabled()) {
                        LOG.debug("Losed file lock [" + fileLock.getId() + LOG_SEP + fileLock.getFile() + "], could not be renewed: " + e.getMessage());
                    }
                }
                return null;
            }));
        } catch (RejectedExecutionException e) {
            // the executors shut down: the JVM is exiting
            if (LOG.isDebugEnabled()) {
                LOG.debug("Could not renew file lock [" + fileLock.getId() + LOG_SEP + fileLock.getFile() + "]: " + e.getMessage());
            }
        }
    }


    /**
     * Schedule the lock handler if it does not run yet.
     */
    private synchronized void scheduleIfNeeded() {
        if (handlerTask == null && !shutdown && !fileLockMap.isEmpty()) {
            if (LOG.isDebugEnabled()) {
                LOG.debug("Start the file lock handler with an interval of " + scheduleInterval + " seconds.");
            }
            handlerTask = LeaderElectionExecutors.getInstance().schedulePeriodic(HANDLER_THREAD_NAME, new FileLockHandler(), scheduleInterval * 1000L);
        }
    }


    /**
     * Stop the lock handler once no file lock exists anymore.
     */
    private synchronized void cancelScheduleIfIdle() {
        if (handlerTask != null && fileLockMap.isEmpty()) {
            handlerTask.cancel();
            handlerTask = null;
            if (LOG.isDebugEnabled()) {
                LOG.debug("Stopped the file lock handler.");
            }
        }
    }


    /**
     * Stop the lock handler for good: the JVM is exiting.
     */
    private synchronized void shutdownSchedule() {
        shutdown = true;
        if (handlerTask != null) {
            handlerTask.cancel();
            handlerTask = null;
        }
        if (LOG.isDebugEnabled()) {
            LOG.debug("Stopped the file lock handler.");
        }
    }
}
