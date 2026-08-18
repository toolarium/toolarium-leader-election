/*
 * ThreadUtilTest.java
 *
 * Copyright by toolarium, all rights reserved.
 */
package com.github.toolarium.leader.election.impl.file;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;


/**
 * Test the ThreadUtil.
 *
 * @author patrick
 */
public class ThreadUtilTest {

    /**
     * Test getInstance returns a non-null singleton.
     */
    @Test
    public void testGetInstance() {
        assertNotNull(ThreadUtil.getInstance());
    }


    /**
     * Test that null id returns false.
     */
    @Test
    public void testNullIdReturnsFalse() {
        assertFalse(ThreadUtil.getInstance().isThreadAlive(null));
    }


    /**
     * Test that the current thread is detected as alive.
     */
    @Test
    public void testCurrentThreadIsAlive() {
        long id = Thread.currentThread().threadId();
        assertTrue(ThreadUtil.getInstance().isThreadAlive(id));
    }


    /**
     * Test that a terminated thread is not alive.
     *
     * @throws InterruptedException in case of interruption
     */
    @Test
    public void testTerminatedThreadIsNotAlive() throws InterruptedException {
        Thread t = new Thread(() -> { });
        t.start();
        long id = t.threadId();
        t.join();
        assertFalse(ThreadUtil.getInstance().isThreadAlive(id));
    }
}
