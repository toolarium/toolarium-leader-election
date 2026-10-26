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
public class LeaderElectionConfiguration implements ILeaderElectionConfiguration {
    // Upper bound of the required gap between timeout and renewDeadline; see validate().
    private static final long MIN_LEASE_SAFETY_MARGIN_MILLIS = 1000L;
    private Duration timeout;
    private Duration renewDeadline;
    private Duration retryPeriod;
    private Duration closeTimeout;

    
    /**
     * Constructor for LeaderElectionConfiguration
     *
     * @throws IllegalArgumentException In case of a parameter failure
     */
    public LeaderElectionConfiguration() throws IllegalArgumentException {
        this(Duration.ofSeconds(15), Duration.ofSeconds(10), Duration.ofSeconds(5));
    }

    
    /**
     * Constructor for LeaderElectionConfiguration
     *
     * @param timeoutInSeconds the timeout
     * @throws IllegalArgumentException In case of a parameter failure
     */
    public LeaderElectionConfiguration(long timeoutInSeconds) throws IllegalArgumentException {
        this.timeout = Duration.ofSeconds(timeoutInSeconds);
        this.retryPeriod = deriveRetryPeriod(timeout);
        this.renewDeadline = deriveRenewDeadline(timeout);
        this.closeTimeout = Duration.ofSeconds(5);
        validate();
    }


    /**
     * Constructor for LeaderElectionConfiguration
     *
     * @param timeout the timeout
     * @param renewDeadline the rewnew deadline
     * @param retryPeriod the retry period
     * @throws IllegalArgumentException In case of a parameter failure
     */
    public LeaderElectionConfiguration(Duration timeout, Duration renewDeadline, Duration retryPeriod) throws IllegalArgumentException {
        this.timeout = timeout;
        this.renewDeadline = renewDeadline;
        this.retryPeriod = retryPeriod;
        this.closeTimeout = Duration.ofSeconds(5);
        validate();
    }


    /**
     * Derive the retry period from the timeout: one third of the timeout.
     *
     * @param timeout the timeout
     * @return the retry period
     */
    public static Duration deriveRetryPeriod(Duration timeout) {
        return timeout.dividedBy(3);
    }


    /**
     * Derive the renew deadline from the timeout: two thirds of the timeout. The renew deadline then
     * covers two retry periods, so a single slow or failed renewal round does not cost the leadership,
     * and one third of the timeout remains as margin before another node may take over.
     *
     * @param timeout the timeout
     * @return the renew deadline
     */
    public static Duration deriveRenewDeadline(Duration timeout) {
        return timeout.multipliedBy(2).dividedBy(3);
    }


    /**
     * Validate
     *  
     * @throws IllegalArgumentException In case of a parameter failure
     */
    public void validate() throws IllegalArgumentException {
        if (timeout == null || timeout.isZero()) {
            throw new IllegalArgumentException("Invalid timeout!");
        } else if (timeout.isNegative()) {
            throw new IllegalArgumentException("Too short timeout.");
        }

        // Compare the exact Duration values: truncated seconds would accept e.g. renewDeadline 1999 ms
        // with timeout 2000 ms, or reject renewDeadline 1500 ms with timeout 1999 ms.
        if (renewDeadline == null || renewDeadline.isZero() || renewDeadline.isNegative() || renewDeadline.compareTo(timeout) >= 0) {
            throw new IllegalArgumentException("Invalid renewDeadline. The renew deadline must be < timeout!");
        }

        // retryPeriod == renewDeadline would let the leadership expire in every round: the next round
        // starts exactly when the renew deadline of the previous one runs out.
        if (retryPeriod == null || retryPeriod.isZero() || retryPeriod.isNegative() || retryPeriod.compareTo(renewDeadline) >= 0) {
            throw new IllegalArgumentException("Invalid retryPeriod. The retry period must be < renewDeadline!");
        }

        // A leader steps down at renewDeadline, another node takes over at timeout. The gap between
        // both is the only protection against clock offset between nodes and statement latency;
        // a gap of a few milliseconds would allow two leaders at the same time.
        long marginMillis = timeout.toMillis() - renewDeadline.toMillis();
        long requiredMarginMillis = Math.min(MIN_LEASE_SAFETY_MARGIN_MILLIS, timeout.toMillis() / 4);
        if (marginMillis < requiredMarginMillis) {
            throw new IllegalArgumentException("Invalid renewDeadline. The gap between timeout and renewDeadline is " + marginMillis
                                               + "ms but must be at least " + requiredMarginMillis + "ms (min(1s, timeout / 4))!");
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
     * @return this configuration instance for method chaining
     */
    public LeaderElectionConfiguration setTimeout(Duration timeout) {
        this.timeout = timeout;
        return this;
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
     * @return this configuration instance for method chaining
     */
    public LeaderElectionConfiguration setRenewDeadline(Duration renewDeadline) {
        this.renewDeadline = renewDeadline;
        return this;
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
     * @return this configuration instance for method chaining
     */
    public LeaderElectionConfiguration setRetryPeriod(Duration retryPeriod) {
        this.retryPeriod = retryPeriod;
        return this;
    }


    /**
     * Get the close timeout, maximum time to wait for background threads or connections to terminate cleanly 
     * during {@link com.github.toolarium.leader.election.ILeaderElector#close()}. Defaults to 5 seconds.
     *
     * @return the close timeout
     */
    public Duration getCloseTimeout() {
        return closeTimeout;
    }


    /**
     * Set the close timeout.
     *
     * @param closeTimeout the close timeout; must be a positive, non-null duration
     * @return this configuration instance for method chaining
     * @throws IllegalArgumentException if closeTimeout is null
     */
    public LeaderElectionConfiguration setCloseTimeout(Duration closeTimeout) {
        if (closeTimeout == null) {
            throw new IllegalArgumentException("closeTimeout must not be null.");
        }
        this.closeTimeout = closeTimeout;
        return this;
    }


    /**
     * @see java.lang.Object#hashCode()
     */
    @Override
    public int hashCode() {
        return Objects.hash(retryPeriod, renewDeadline, timeout, closeTimeout);
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
        return Objects.equals(retryPeriod, other.retryPeriod) && Objects.equals(renewDeadline, other.renewDeadline) && Objects.equals(timeout, other.timeout) && Objects.equals(closeTimeout, other.closeTimeout);
    }


    /**
     * @see java.lang.Object#toString()
     */
    @Override
    public String toString() {
        return "LeaderElectionConfiguration [timeout=" + timeout + ", renewDeadline=" + renewDeadline + ", retryPeriod=" + retryPeriod + ", closeTimeout=" + closeTimeout + "]";
    }
}
