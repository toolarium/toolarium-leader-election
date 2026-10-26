/*
 * LeaderElectionExecutors.java
 *
 * Copyright by toolarium, all rights reserved.
 */
package com.github.toolarium.leader.election.impl;

import java.util.concurrent.Callable;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


/**
 * The threads shared by all leader electors of the JVM, so the number of threads does not grow with the number of electors:
 * <ul>
 *   <li>one timer thread that only triggers periodic election rounds,</li>
 *   <li>a worker pool that runs the rounds and lease extensions; it creates a thread only while work is running, so a slow
 *       elector never delays the rounds of the others, and idle threads end after {@value #KEEP_ALIVE_SECONDS} s,</li>
 *   <li>one thread that checks the leases of running leader tasks.</li>
 * </ul>
 * All threads are daemon threads and end when they are idle, so a JVM without active electors has none of them.
 *
 * @author patrick
 */
public final class LeaderElectionExecutors {
    private static final Logger LOG = LoggerFactory.getLogger(LeaderElectionExecutors.class);
    private static final long KEEP_ALIVE_SECONDS = 60L;
    private final ScheduledThreadPoolExecutor timer;
    private final ScheduledThreadPoolExecutor leaseMonitor;
    private final ThreadPoolExecutor workers;


    /**
     * Private class, the only instance of the singelton which will be created by accessing the holder class.
     *
     * @author patrick
     */
    private static final class HOLDER {
        static final LeaderElectionExecutors INSTANCE = new LeaderElectionExecutors();
    }


    /**
     * Constructor
     */
    private LeaderElectionExecutors() {
        timer = createScheduler("leader-election-timer");
        leaseMonitor = createScheduler("leader-lease-monitor");
        workers = new ThreadPoolExecutor(0, Integer.MAX_VALUE, KEEP_ALIVE_SECONDS, TimeUnit.SECONDS, new SynchronousQueue<>(), createThreadFactory("leader-election-worker-", true));
    }


    /**
     * Get the instance
     *
     * @return the instance
     */
    public static LeaderElectionExecutors getInstance() {
        return HOLDER.INSTANCE;
    }


    /**
     * Run a task periodically on the worker pool. The first run starts immediately. A run is skipped while the previous run
     * of the same task is still active, so runs of one task never overlap. While running, the worker thread carries the
     * given name.
     *
     * <p>The second run starts at a random point between half a period and a full period after the first, so tasks created
     * at the same moment (e.g. all electors at startup) do not run at the same moment and do not borrow their resources, such
     * as database connections, all at once. The gap between two runs never exceeds the period.
     *
     * @param threadName the thread name while the task runs
     * @param task the task
     * @param periodMillis the period in milliseconds
     * @return the periodic task
     */
    public PeriodicTask schedulePeriodic(String threadName, Runnable task, long periodMillis) {
        PeriodicTask periodicTask = new PeriodicTask(workers, threadName, task, periodMillis);
        timer.execute(periodicTask::dispatch);
        long initialDelayMillis = periodMillis - ThreadLocalRandom.current().nextLong(periodMillis / 2 + 1);
        periodicTask.future = timer.scheduleAtFixedRate(periodicTask::dispatch, initialDelayMillis, periodMillis, TimeUnit.MILLISECONDS);
        return periodicTask;
    }


    /**
     * Run a short task once on the timer thread after a delay. The task must not block.
     *
     * @param task the task
     * @param delayMillis the delay in milliseconds
     * @return the future of the task
     */
    public ScheduledFuture<?> scheduleOnce(Runnable task, long delayMillis) {
        return timer.schedule(task, delayMillis, TimeUnit.MILLISECONDS);
    }


    /**
     * Run a task once on the worker pool. While running, the worker thread carries the given name.
     *
     * @param <T> the result type
     * @param threadName the thread name while the task runs
     * @param task the task
     * @return the future of the result
     * @throws RejectedExecutionException in case the task could not be started
     */
    public <T> Future<T> submit(String threadName, Callable<T> task) {
        return workers.submit(() -> {
            Thread current = Thread.currentThread();
            String originalName = current.getName();
            current.setName(threadName);
            try {
                return task.call();
            } finally {
                current.setName(originalName);
            }
        });
    }


    /**
     * Gets the scheduler that checks the leases of running leader tasks. Its tasks must be short and must not block.
     *
     * @return the lease monitor scheduler
     */
    public ScheduledExecutorService getLeaseMonitorScheduler() {
        return leaseMonitor;
    }


    /**
     * Create a scheduler with one daemon thread that ends when idle.
     *
     * @param threadName the thread name
     * @return the scheduler
     */
    private static ScheduledThreadPoolExecutor createScheduler(String threadName) {
        ScheduledThreadPoolExecutor scheduler = new ScheduledThreadPoolExecutor(1, createThreadFactory(threadName, false));
        scheduler.setKeepAliveTime(KEEP_ALIVE_SECONDS, TimeUnit.SECONDS);
        scheduler.allowCoreThreadTimeOut(true);
        scheduler.setRemoveOnCancelPolicy(true);
        return scheduler;
    }


    /**
     * Create a factory for daemon threads.
     *
     * @param name the thread name, or the name prefix
     * @param numbered true to append a sequence number to the name
     * @return the thread factory
     */
    private static ThreadFactory createThreadFactory(String name, boolean numbered) {
        AtomicInteger counter = new AtomicInteger();
        return runnable -> {
            String threadName = name;
            if (numbered) {
                threadName = name + counter.incrementAndGet();
            }
            Thread thread = new Thread(runnable, threadName);
            thread.setDaemon(true);
            return thread;
        };
    }


    /**
     * A periodic task: the timer dispatches each run to the worker pool.
     */
    public static final class PeriodicTask {
        // a run that lasts longer than this many periods is reported once
        private static final long OVERRUN_PERIODS = 3L;
        private final ThreadPoolExecutor workers;
        private final String threadName;
        private final Runnable task;
        private final long overrunNanos;
        private final AtomicBoolean running = new AtomicBoolean(false);
        private final AtomicBoolean overrunReported = new AtomicBoolean(false);
        // set when a run was skipped because the previous one was still active: the missed run follows right after it
        private final AtomicBoolean runMissed = new AtomicBoolean(false);
        // true once a failure was warned about: further failures are logged at debug level until a run succeeds
        private final AtomicBoolean failureReported = new AtomicBoolean(false);
        private volatile boolean cancelled;
        private volatile long runStartNanos;
        private volatile ScheduledFuture<?> future;


        /**
         * Constructor for PeriodicTask
         *
         * @param workers the worker pool
         * @param threadName the thread name while the task runs
         * @param task the task
         * @param periodMillis the period in milliseconds
         */
        private PeriodicTask(ThreadPoolExecutor workers, String threadName, Runnable task, long periodMillis) {
            this.workers = workers;
            this.threadName = threadName;
            this.task = task;
            this.overrunNanos = TimeUnit.MILLISECONDS.toNanos(periodMillis) * OVERRUN_PERIODS;
            this.cancelled = false;
        }


        /**
         * Stop the periodic runs. A run that is already active is not interrupted; use {@link #awaitIdle(long)} to wait for it.
         */
        public void cancel() {
            // set before cancelling the timer: a dispatch that races with cancel() checks the flag after claiming the run
            cancelled = true;
            ScheduledFuture<?> f = future;
            if (f != null) {
                f.cancel(false);
            }
        }


        /**
         * Wait until no run of this task is active.
         *
         * @param timeoutMillis the maximum time to wait
         * @return true if no run is active
         * @throws InterruptedException if interrupted while waiting
         */
        public boolean awaitIdle(long timeoutMillis) throws InterruptedException {
            long deadlineNanos = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis);
            synchronized (this) {
                while (running.get()) {
                    long remainingMillis = TimeUnit.NANOSECONDS.toMillis(deadlineNanos - System.nanoTime());
                    if (remainingMillis <= 0) {
                        return false;
                    }
                    wait(remainingMillis);
                }
            }
            return true;
        }


        /**
         * Hand one run to the worker pool, unless the previous run is still active or the task is cancelled. Called by the timer.
         */
        private void dispatch() {
            if (cancelled) {
                // cancel() may have run before the future was assigned: stop the timer from here
                ScheduledFuture<?> f = future;
                if (f != null) {
                    f.cancel(false);
                }
                return;
            }
            if (!running.compareAndSet(false, true)) {
                runMissed.set(true);
                reportOverrun();
                return;
            }
            if (cancelled) {
                finished();
                return;
            }
            try {
                workers.execute(this::run);
            } catch (RejectedExecutionException e) {
                finished();
            }
        }


        /**
         * Run the task on a worker thread under the task's thread name.
         */
        private void run() {
            runStartNanos = System.nanoTime();
            overrunReported.set(false);
            Thread current = Thread.currentThread();
            String originalName = current.getName();
            current.setName(threadName);
            try {
                task.run();
                if (failureReported.compareAndSet(true, false)) {
                    LOG.info("[{}] runs again without errors.", threadName);
                }
            } catch (RuntimeException e) {
                // keep the periodic runs alive: the next run retries. The tasks handle their expected failures themselves, so this is
                // unexpected: warned once until a run succeeds again.
                if (LOG.isDebugEnabled()) {
                    LOG.debug("Unexpected error in [{}]: {}", threadName, e.toString(), e);
                }
                if (failureReported.compareAndSet(false, true)) {
                    LOG.warn("Unexpected error in [{}]: {}. Further errors are logged at debug level until a run succeeds. Please report it, "
                             + "if possible with the stack trace (enable debug logging).", threadName, e.toString());
                }
            } finally {
                current.setName(originalName);
                finished();
            }
            // A run that took longer than the period made the next one wait for the following period: it runs now instead, e.g. so
            // that a slow database round is followed by a renewal well before the renew deadline.
            if (runMissed.getAndSet(false)) {
                dispatch();
            }
        }


        /**
         * Report once per run that the active run takes much longer than the period, e.g. because it hangs in a database
         * call; until it finishes, all further runs are skipped.
         */
        private void reportOverrun() {
            long durationNanos = System.nanoTime() - runStartNanos;
            if (durationNanos > overrunNanos && overrunReported.compareAndSet(false, true)) {
                LOG.warn("[{}] is still running after {} ms; further runs are skipped until it finishes. Check that the backend (database, "
                         + "Kubernetes API, file system) answers and configure its connection and query timeouts below the renew deadline.",
                         threadName, TimeUnit.NANOSECONDS.toMillis(durationNanos));
            }
        }


        /**
         * Mark the active run as finished and wake up threads waiting in {@link #awaitIdle(long)}.
         */
        private void finished() {
            synchronized (this) {
                running.set(false);
                notifyAll();
            }
        }
    }
}
