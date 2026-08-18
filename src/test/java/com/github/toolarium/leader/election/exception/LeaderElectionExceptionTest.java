/*
 * LeaderElectionExceptionTest.java
 *
 * Copyright by toolarium, all rights reserved.
 */
package com.github.toolarium.leader.election.exception;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

import org.junit.jupiter.api.Test;


/**
 * Test the leader election exception.
 *
 * @author patrick
 */
public class LeaderElectionExceptionTest {

    /**
     * Test message constructor.
     */
    @Test
    public void testMessageConstructor() {
        LeaderElectionException ex = new LeaderElectionException("test error");
        assertEquals("test error", ex.getMessage());
    }


    /**
     * Test message and cause constructor.
     */
    @Test
    public void testMessageAndCauseConstructor() {
        RuntimeException cause = new RuntimeException("root cause");
        LeaderElectionException ex = new LeaderElectionException("test error", cause);
        assertEquals("test error", ex.getMessage());
        assertSame(cause, ex.getCause());
    }
}
