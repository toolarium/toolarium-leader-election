/*
 * ThreadUtil.java
 *
 * Copyright by toolarium, all rights reserved.
 */
package com.github.toolarium.leader.election.impl.file;

/**
 * Thread utility
 *
 * @author patrick
 */
public final class ThreadUtil {

    /**
     * Private class, the only instance of the singelton which will be created by accessing the holder class.
     *
     * @author patrick
     */
    private static final class HOLDER {
        static final ThreadUtil INSTANCE = new ThreadUtil();
    }

    /**
     * Constructor
     */
    private ThreadUtil() {
        // NOP
    }

    /**
     * Get the instance
     *
     * @return the instance
     */
    public static ThreadUtil getInstance() {
        return HOLDER.INSTANCE;
    }


    /**
     * Returns the id of the current thread, see {@link #getThreadId(Thread)}.
     *
     * @return the current thread id
     */
    public long getThreadId() {
        return getThreadId(Thread.currentThread());
    }


    /**
     * Returns the id of thread.
     * Uses {@link Thread#getId()}, deprecated since Java 19, for compatibility with older runtimes; the deprecation
     * warning is suppressed. Once the minimum runtime is Java 19, replace it with {@code thread.threadId()} and remove
     * the suppression.
     * 
     * @param thread the cthread
     * @return the current thread id
     */
    @SuppressWarnings("deprecation")
    public long getThreadId(Thread thread) {
        return thread.getId();
    }


    /**
     * Check if a specific thread is still alive
     *
     * @param id the thread id
     * @return true if it is alive
     */
    public boolean isThreadAlive(Long id) {
        if (id == null) {
            return false;
        }

        try {
            for (Thread thread : Thread.getAllStackTraces().keySet()) {
                if (id == getThreadId(thread)) {
                    return thread.isAlive();
                }
            }
        } catch (Exception e) {
            // NOP
        }

        return false;
    }
}
