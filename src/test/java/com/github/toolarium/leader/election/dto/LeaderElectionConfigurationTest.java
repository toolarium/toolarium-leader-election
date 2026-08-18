/*
 * LeaderElectionConfigurationTest.java
 *
 * Copyright by toolarium, all rights reserved.
 */
package com.github.toolarium.leader.election.dto;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Duration;
import org.junit.jupiter.api.Test;


/**
 * Test the leader election configuration.
 *
 * @author patrick
 */
public class LeaderElectionConfigurationTest {

    /**
     * Test the default constructor produces a valid 10-second configuration.
     */
    @Test
    public void testDefaultConstructor() {
        LeaderElectionConfiguration config = new LeaderElectionConfiguration();
        assertEquals(Duration.ofSeconds(10), config.getTimeout());
        assertEquals(Duration.ofSeconds(5), config.getRenewDeadline());
        assertEquals(Duration.ofSeconds(5), config.getRetryPeriod());
    }


    /**
     * Test the timeout-based constructor with a value of 2 (the minimal working value).
     */
    @Test
    public void testTimeoutConstructorWithTwo() {
        LeaderElectionConfiguration config = new LeaderElectionConfiguration(2);
        assertEquals(Duration.ofSeconds(2), config.getTimeout());
    }


    /**
     * Test explicit Duration constructor.
     */
    @Test
    public void testDurationConstructor() {
        Duration timeout = Duration.ofSeconds(30);
        Duration renewDeadline = Duration.ofSeconds(15);
        Duration retryPeriod = Duration.ofSeconds(15);
        LeaderElectionConfiguration config = new LeaderElectionConfiguration(timeout, renewDeadline, retryPeriod);
        assertEquals(timeout, config.getTimeout());
        assertEquals(renewDeadline, config.getRenewDeadline());
        assertEquals(retryPeriod, config.getRetryPeriod());
    }


    /**
     * Test that null timeout throws IllegalArgumentException.
     */
    @Test
    public void testNullTimeoutThrows() {
        assertThrows(IllegalArgumentException.class, () ->
            new LeaderElectionConfiguration(null, Duration.ofSeconds(5), Duration.ofSeconds(5)));
    }


    /**
     * Test that zero timeout throws IllegalArgumentException.
     */
    @Test
    public void testZeroTimeoutThrows() {
        assertThrows(IllegalArgumentException.class, () ->
            new LeaderElectionConfiguration(Duration.ZERO, Duration.ofSeconds(5), Duration.ofSeconds(5)));
    }


    /**
     * Test that renewDeadline >= timeout throws IllegalArgumentException.
     */
    @Test
    public void testRenewDeadlineEqualToTimeoutThrows() {
        assertThrows(IllegalArgumentException.class, () ->
            new LeaderElectionConfiguration(Duration.ofSeconds(10), Duration.ofSeconds(10), Duration.ofSeconds(5)));
    }


    /**
     * Test that renewDeadline > retryPeriod throws IllegalArgumentException.
     */
    @Test
    public void testRenewDeadlineGreaterThanRetryPeriodThrows() {
        assertThrows(IllegalArgumentException.class, () ->
            new LeaderElectionConfiguration(Duration.ofSeconds(10), Duration.ofSeconds(7), Duration.ofSeconds(5)));
    }


    /**
     * Test that null renewDeadline throws IllegalArgumentException.
     */
    @Test
    public void testNullRenewDeadlineThrows() {
        assertThrows(IllegalArgumentException.class, () ->
            new LeaderElectionConfiguration(Duration.ofSeconds(10), null, Duration.ofSeconds(5)));
    }


    /**
     * Test that null retryPeriod throws IllegalArgumentException.
     */
    @Test
    public void testNullRetryPeriodThrows() {
        assertThrows(IllegalArgumentException.class, () ->
            new LeaderElectionConfiguration(Duration.ofSeconds(10), Duration.ofSeconds(5), null));
    }


    /**
     * Test equals and hashCode.
     */
    @Test
    public void testEqualsAndHashCode() {
        LeaderElectionConfiguration a = new LeaderElectionConfiguration(Duration.ofSeconds(10), Duration.ofSeconds(5), Duration.ofSeconds(5));
        LeaderElectionConfiguration b = new LeaderElectionConfiguration(Duration.ofSeconds(10), Duration.ofSeconds(5), Duration.ofSeconds(5));
        LeaderElectionConfiguration c = new LeaderElectionConfiguration(Duration.ofSeconds(20), Duration.ofSeconds(9), Duration.ofSeconds(9));

        assertEquals(a, b);
        assertEquals(a.hashCode(), b.hashCode());
        assertNotEquals(a, c);
        assertEquals(a, a);
        assertNotEquals(a, null);
        assertNotEquals(a, "other");
    }


    /**
     * Test toString contains key fields.
     */
    @Test
    public void testToString() {
        LeaderElectionConfiguration config = new LeaderElectionConfiguration(Duration.ofSeconds(10), Duration.ofSeconds(5), Duration.ofSeconds(5));
        String s = config.toString();
        assertEquals("LeaderElectionConfiguration [timeout=PT10S, renewDeadline=PT5S, retryPeriod=PT5S]", s);
    }


    /**
     * Test setters.
     */
    @Test
    public void testSetters() {
        LeaderElectionConfiguration config = new LeaderElectionConfiguration(Duration.ofSeconds(10), Duration.ofSeconds(5), Duration.ofSeconds(5));
        config.setTimeout(Duration.ofSeconds(20));
        config.setRenewDeadline(Duration.ofSeconds(10));
        config.setRetryPeriod(Duration.ofSeconds(8));
        assertEquals(Duration.ofSeconds(20), config.getTimeout());
        assertEquals(Duration.ofSeconds(10), config.getRenewDeadline());
        assertEquals(Duration.ofSeconds(8), config.getRetryPeriod());
    }
}
