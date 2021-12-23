/*
 * LeaderElectionConfiguration.java
 *
 * Copyright by toolarium, all rights reserved.
 */
package com.github.toolarium.leader.election.dto;

import java.time.Duration;
import java.util.Objects;

/**
 * The leader election configuration
 * 
 * @author patrick
 */
public class LeaderElectionConfiguration {
    private Duration timeout;
    private Duration renewDeadline;
    private Duration retryPeriod;

    
    /**
     * Constructor for LeaderElectionConfiguration
     * 
     * @throws IllegalArgumentException In case of a parameter failure
     */
    public LeaderElectionConfiguration() throws IllegalArgumentException {
        this(10);
    }

    
    /**
     * Constructor for LeaderElectionConfiguration
     *
     * @param timeoutInSeconds the timeout
     * @throws IllegalArgumentException In case of a parameter failure
     */
    public LeaderElectionConfiguration(long timeoutInSeconds) throws IllegalArgumentException {
        this.timeout = Duration.ofSeconds(timeoutInSeconds);
        this.retryPeriod = Duration.ofSeconds(timeoutInSeconds).dividedBy(2);
        this.renewDeadline = Duration.ofSeconds(timeoutInSeconds - retryPeriod.toSeconds());
        if (renewDeadline.toSeconds() <= retryPeriod.toSeconds()) {
            this.renewDeadline = renewDeadline.plus(renewDeadline.dividedBy(2));
        }
        
        validate();
    }


    
    /**
     * Constructor for LeaderElectionConfiguration
     *
     * @param timeout the timeout
     * @param renewDeadline the ewnew deadline
     * @param retryPeriod the retry period
     * @throws IllegalArgumentException In case of a parameter failure
     */
    public LeaderElectionConfiguration(Duration timeout, Duration renewDeadline, Duration retryPeriod) throws IllegalArgumentException {
        this.timeout = timeout;
        this.renewDeadline = renewDeadline;
        this.retryPeriod = retryPeriod;
        
        validate();
    }


    /**
     * Validate 
     * @throws IllegalArgumentException In case of a parameter failure
     */
    private void validate() throws IllegalArgumentException {
        if (timeout == null || timeout.isZero()) {
            throw new IllegalArgumentException("Invalid timeout!");
        }

        if (renewDeadline == null || renewDeadline.isZero() || renewDeadline.toSeconds() >= timeout.toSeconds()) {
            throw new IllegalArgumentException("Invalid renewDeadline. The renew deadline must be < timeout!");
        }

        if (retryPeriod == null || retryPeriod.isZero() || renewDeadline.toSeconds() > retryPeriod.toSeconds()) {
            throw new IllegalArgumentException("Invalid retryPeriod. The retry period must be < renewDeadline!");
        }
    }

    
    /**
     * Get the timeout
     *
     * @return the timeout
     */
    public Duration getTimeout() {
        return timeout;
    }

    
    /**
     * Set the timeout
     *
     * @param timeout the timeout
     */
    public void setTimeout(Duration timeout) {
        this.timeout = timeout;
    }

    
    /**
     * Get the renew deadline
     *
     * @return the renew deadline
     */
    public Duration getRenewDeadline() {
        return renewDeadline;
    }

    
    /**
     * Set the renew deadline
     *
     * @param renewDeadline the renew deadline
     */
    public void setRenewDeadline(Duration renewDeadline) {
        this.renewDeadline = renewDeadline;
    }

    
    /**
     * Get the retry period
     *
     * @return the retry period
     */
    public Duration getRetryPeriod() {
        return retryPeriod;
    }

    
    /**
     * Set the retry period
     *
     * @param retryPeriod the retry period
     */
    public void setRetryPeriod(Duration retryPeriod) {
        this.retryPeriod = retryPeriod;
    }


    /**
     * @see java.lang.Object#hashCode()
     */
    @Override
    public int hashCode() {
        return Objects.hash(retryPeriod, renewDeadline, timeout);
    }


    /**
     * @see java.lang.Object#equals(java.lang.Object)
     */
    @Override
    public boolean equals(Object obj) {
        if (this == obj) {
            return true;
        }
        
        if (obj == null) {
            return false;
        }
        
        if (getClass() != obj.getClass()) {            
            return false;
        }
        
        LeaderElectionConfiguration other = (LeaderElectionConfiguration) obj;
        return Objects.equals(retryPeriod, other.retryPeriod) && Objects.equals(renewDeadline, other.renewDeadline) && Objects.equals(timeout, other.timeout);
    }


    /**
     * @see java.lang.Object#toString()
     */
    @Override
    public String toString() {
        return "LeaderElectionConfiguration [timeout=" + timeout + ", renewDeadline=" + renewDeadline + ", retryPeriod=" + retryPeriod + "]";
    }
}
