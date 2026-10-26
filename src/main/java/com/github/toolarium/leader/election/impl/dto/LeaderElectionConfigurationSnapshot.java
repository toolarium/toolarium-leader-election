/*
 * LeaderElectionConfigurationSnapshot.java
 *
 * Copyright by toolarium, all rights reserved.
 */
package com.github.toolarium.leader.election.impl.dto;

import com.github.toolarium.leader.election.dto.ILeaderElectionConfiguration;
import java.time.Duration;


/**
 * An immutable copy of the timings of a leader election configuration for the administration API. It holds nothing else:
 * the configuration of a strategy may contain secrets or resources (e.g. an encryption key or a DataSource), and changing
 * it at runtime could break the election.
 *
 * @author patrick
 */
public final class LeaderElectionConfigurationSnapshot implements ILeaderElectionConfiguration {
    private final Duration timeout;
    private final Duration renewDeadline;
    private final Duration retryPeriod;
    private final Duration closeTimeout;


    /**
     * Constructor for LeaderElectionConfigurationSnapshot
     *
     * @param configuration the configuration to copy, or null
     */
    public LeaderElectionConfigurationSnapshot(ILeaderElectionConfiguration configuration) {
        if (configuration == null) {
            this.timeout = null;
            this.renewDeadline = null;
            this.retryPeriod = null;
            this.closeTimeout = null;
        } else {
            this.timeout = configuration.getTimeout();
            this.renewDeadline = configuration.getRenewDeadline();
            this.retryPeriod = configuration.getRetryPeriod();
            this.closeTimeout = configuration.getCloseTimeout();
        }
    }


    /**
     * @see com.github.toolarium.leader.election.dto.ILeaderElectionConfiguration#getTimeout()
     */
    @Override
    public Duration getTimeout() {
        return timeout;
    }


    /**
     * @see com.github.toolarium.leader.election.dto.ILeaderElectionConfiguration#getRenewDeadline()
     */
    @Override
    public Duration getRenewDeadline() {
        return renewDeadline;
    }


    /**
     * @see com.github.toolarium.leader.election.dto.ILeaderElectionConfiguration#getRetryPeriod()
     */
    @Override
    public Duration getRetryPeriod() {
        return retryPeriod;
    }


    /**
     * @see com.github.toolarium.leader.election.dto.ILeaderElectionConfiguration#getCloseTimeout()
     */
    @Override
    public Duration getCloseTimeout() {
        return closeTimeout;
    }


    /**
     * @see java.lang.Object#toString()
     */
    @Override
    public String toString() {
        return "LeaderElectionConfigurationSnapshot [timeout=" + timeout + ", renewDeadline=" + renewDeadline + ", retryPeriod=" + retryPeriod
               + ", closeTimeout=" + closeTimeout + "]";
    }
}
