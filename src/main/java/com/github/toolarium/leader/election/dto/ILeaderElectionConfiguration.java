/*
 * ILeaderElectionConfiguration.java
 *
 * Copyright by toolarium, all rights reserved.
 */
package com.github.toolarium.leader.election.dto;

import java.time.Duration;


/**
 * Read-only view of the leader election timing configuration.
 *
 * @author patrick
 */
public interface ILeaderElectionConfiguration {

    /**
     * How long a lease is valid. Another node may take over after this elapses.
     *
     * @return the timeout; never {@code null}
     */
    Duration getTimeout();


    /**
     * The leader must renew its lease within this duration. Must be less than {@link #getTimeout()}.
     *
     * @return the renew deadline; never {@code null}
     */
    Duration getRenewDeadline();


    /**
     * How often candidates attempt to acquire or renew the lease. Must be less than or equal to {@link #getRenewDeadline()}.
     *
     * @return the retry period; never {@code null}
     */
    Duration getRetryPeriod();


    /**
     * Maximum time to wait for a clean shutdown when {@code close()} is called.
     *
     * @return the close timeout; never {@code null}
     */
    Duration getCloseTimeout();
}
