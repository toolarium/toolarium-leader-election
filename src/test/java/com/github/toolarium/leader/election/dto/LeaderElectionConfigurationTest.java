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
     * Test the default constructor produces a valid 15-second configuration whose renew deadline
     * covers two retry periods.
     */
    @Test
    public void testDefaultConstructor() {
        LeaderElectionConfiguration config = new LeaderElectionConfiguration();
        assertEquals(Duration.ofSeconds(15), config.getTimeout());
        assertEquals(Duration.ofSeconds(10), config.getRenewDeadline());
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
     * Test that the timeout-based constructor derives retryPeriod = timeout / 3 and
     * renewDeadline = 2 * timeout / 3, so the renew deadline covers two rounds.
     */
    @Test
    public void testTimeoutConstructorDerivation() {
        LeaderElectionConfiguration config = new LeaderElectionConfiguration(15);
        assertEquals(Duration.ofSeconds(5), config.getRetryPeriod());
        assertEquals(Duration.ofSeconds(10), config.getRenewDeadline());
        assertEquals(new LeaderElectionConfiguration(), config);
    }


    /**
     * Test that renewDeadline == retryPeriod is rejected: the leadership would expire in every round.
     */
    @Test
    public void testRetryPeriodEqualToRenewDeadlineThrows() {
        assertThrows(IllegalArgumentException.class, () ->
            new LeaderElectionConfiguration(Duration.ofSeconds(10), Duration.ofSeconds(5), Duration.ofSeconds(5)));
        assertThrows(IllegalArgumentException.class, () ->
            new LeaderElectionConfiguration(Duration.ofMillis(10000), Duration.ofMillis(5000), Duration.ofMillis(5900)));
    }


    /**
     * Test that the validation compares exact durations and not truncated seconds.
     */
    @Test
    public void testValidationUsesExactDurations() {
        // 1999 ms < 2000 ms in truncated seconds would be 1 < 2 and wrongly accepted as "far below"
        assertThrows(IllegalArgumentException.class, () ->
            new LeaderElectionConfiguration(Duration.ofMillis(2000), Duration.ofMillis(1999), Duration.ofMillis(500)));
        // truncated seconds would compare 1 >= 1 and wrongly reject this valid configuration
        assertEquals(Duration.ofMillis(1400), new LeaderElectionConfiguration(Duration.ofMillis(1900), Duration.ofMillis(1400), Duration.ofMillis(700)).getRenewDeadline());
    }


    /**
     * Test explicit Duration constructor.
     */
    @Test
    public void testDurationConstructor() {
        Duration timeout = Duration.ofSeconds(30);
        Duration renewDeadline = Duration.ofSeconds(20);
        Duration retryPeriod = Duration.ofSeconds(10);
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
     * Test that a too small gap between timeout and renewDeadline throws IllegalArgumentException,
     * while the minimum gap of min(1s, timeout / 4) is accepted.
     */
    @Test
    public void testLeaseSafetyMargin() {
        assertThrows(IllegalArgumentException.class, () ->
            new LeaderElectionConfiguration(Duration.ofMillis(10000), Duration.ofMillis(9999), Duration.ofSeconds(5)));
        assertThrows(IllegalArgumentException.class, () ->
            new LeaderElectionConfiguration(Duration.ofMillis(10000), Duration.ofMillis(9500), Duration.ofSeconds(5)));
        assertEquals(Duration.ofMillis(9000), new LeaderElectionConfiguration(Duration.ofMillis(10000), Duration.ofMillis(9000), Duration.ofSeconds(5)).getRenewDeadline());
        // short timeouts: the required gap scales down to timeout / 4
        assertEquals(Duration.ofSeconds(4).dividedBy(3), new LeaderElectionConfiguration(2).getRenewDeadline());
    }


    /**
     * Test that retryPeriod > renewDeadline throws IllegalArgumentException.
     */
    @Test
    public void testRetryPeriodGreaterThanRenewDeadlineThrows() {
        assertThrows(IllegalArgumentException.class, () ->
            new LeaderElectionConfiguration(Duration.ofSeconds(10), Duration.ofSeconds(3), Duration.ofSeconds(5)));
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
        LeaderElectionConfiguration a = new LeaderElectionConfiguration(Duration.ofSeconds(15), Duration.ofSeconds(10), Duration.ofSeconds(5));
        LeaderElectionConfiguration b = new LeaderElectionConfiguration(Duration.ofSeconds(15), Duration.ofSeconds(10), Duration.ofSeconds(5));
        LeaderElectionConfiguration c = new LeaderElectionConfiguration(Duration.ofSeconds(20), Duration.ofSeconds(12), Duration.ofSeconds(6));

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
        LeaderElectionConfiguration config = new LeaderElectionConfiguration(Duration.ofSeconds(15), Duration.ofSeconds(10), Duration.ofSeconds(5));
        String s = config.toString();
        assertEquals("LeaderElectionConfiguration [timeout=PT15S, renewDeadline=PT10S, retryPeriod=PT5S, closeTimeout=PT5S]", s);
    }


    /**
     * Test that setCloseTimeout(null) throws IllegalArgumentException.
     */
    @Test
    public void testSetCloseTimeoutNullThrows() {
        LeaderElectionConfiguration config = new LeaderElectionConfiguration();
        assertThrows(IllegalArgumentException.class, () -> config.setCloseTimeout(null));
    }


    /**
     * Test that two configurations with the same timeout/renewDeadline/retryPeriod but different
     * closeTimeout values are NOT equal and have different hash codes.
     */
    @Test
    public void testEqualsWithDifferentCloseTimeout() {
        LeaderElectionConfiguration a = new LeaderElectionConfiguration(
                Duration.ofSeconds(15), Duration.ofSeconds(10), Duration.ofSeconds(5));
        LeaderElectionConfiguration b = new LeaderElectionConfiguration(
                Duration.ofSeconds(15), Duration.ofSeconds(10), Duration.ofSeconds(5));
        b.setCloseTimeout(Duration.ofSeconds(30));
        assertNotEquals(a, b, "Configurations with different closeTimeout must not be equal");
        assertNotEquals(a.hashCode(), b.hashCode(),
                "Hash codes must differ for configurations with different closeTimeout");
    }


    /**
     * Test setters.
     */
    @Test
    public void testSetters() {
        LeaderElectionConfiguration config = new LeaderElectionConfiguration(Duration.ofSeconds(15), Duration.ofSeconds(10), Duration.ofSeconds(5));
        config.setTimeout(Duration.ofSeconds(20));
        config.setRenewDeadline(Duration.ofSeconds(10));
        config.setRetryPeriod(Duration.ofSeconds(8));
        assertEquals(Duration.ofSeconds(20), config.getTimeout());
        assertEquals(Duration.ofSeconds(10), config.getRenewDeadline());
        assertEquals(Duration.ofSeconds(8), config.getRetryPeriod());
    }
}
