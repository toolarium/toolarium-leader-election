/*
 * KubernetesLeaderElectionConfiguration.java
 *
 * Copyright by toolarium, all rights reserved.
 */
package com.github.toolarium.leader.election.dto.kubernetes;

import com.github.toolarium.leader.election.dto.LeaderElectionConfiguration;
import java.time.Duration;
import java.util.Objects;


/**
 * The leader election kubernetes configuration.
 *
 * <p>The lock type selects the Kubernetes resource that holds the lock. The default is
 * {@link KubernetesLockType#LEASE}. Nodes using different lock types do not see each other's lock,
 * so all nodes of an election must use the same lock type.
 *
 * @author patrick
 */
public class KubernetesLeaderElectionConfiguration extends LeaderElectionConfiguration {
    /** The default lock type. */
    public static final KubernetesLockType DEFAULT_LOCK_TYPE = KubernetesLockType.LEASE;
    private KubernetesLockType lockType;


    /**
     * Constructor for KubernetesLeaderElectionConfiguration
     *
     * @throws IllegalArgumentException In case of a parameter failure
     */
    public KubernetesLeaderElectionConfiguration() throws IllegalArgumentException {
        super();
        lockType = DEFAULT_LOCK_TYPE;
    }


    /**
     * Constructor for KubernetesLeaderElectionConfiguration
     *
     * @param timeout the timeout
     * @param renewDeadline the renew deadline
     * @param retryPeriod the retry period
     * @throws IllegalArgumentException In case of a parameter failure
     */
    public KubernetesLeaderElectionConfiguration(Duration timeout, Duration renewDeadline, Duration retryPeriod) throws IllegalArgumentException {
        super(timeout, renewDeadline, retryPeriod);
        lockType = DEFAULT_LOCK_TYPE;
    }


    /**
     * Constructor for KubernetesLeaderElectionConfiguration
     *
     * @param lockType the lock type
     * @param timeout the timeout
     * @param renewDeadline the renew deadline
     * @param retryPeriod the retry period
     * @throws IllegalArgumentException In case of a parameter failure
     */
    public KubernetesLeaderElectionConfiguration(KubernetesLockType lockType, Duration timeout, Duration renewDeadline, Duration retryPeriod) throws IllegalArgumentException {
        super(timeout, renewDeadline, retryPeriod);
        setLockType(lockType);
    }


    /**
     * Gets the lock type.
     *
     * @return the lock type
     */
    public KubernetesLockType getLockType() {
        return lockType;
    }


    /**
     * Sets the lock type.
     *
     * @param lockType the lock type, not null
     * @throws IllegalArgumentException In case the lock type is null
     */
    public void setLockType(KubernetesLockType lockType) throws IllegalArgumentException {
        if (lockType == null) {
            throw new IllegalArgumentException("Invalid lockType: must not be null!");
        }
        this.lockType = lockType;
    }


    /**
     * @see com.github.toolarium.leader.election.dto.LeaderElectionConfiguration#hashCode()
     */
    @Override
    public int hashCode() {
        return Objects.hash(super.hashCode(), lockType);
    }


    /**
     * @see com.github.toolarium.leader.election.dto.LeaderElectionConfiguration#equals(java.lang.Object)
     */
    @Override
    public boolean equals(Object obj) {
        if (!super.equals(obj)) {
            return false;
        }
        return lockType == ((KubernetesLeaderElectionConfiguration) obj).lockType;
    }


    /**
     * @see com.github.toolarium.leader.election.dto.LeaderElectionConfiguration#toString()
     */
    @Override
    public String toString() {
        return "KubernetesLeaderElectionConfiguration [timeout=" + getTimeout() + ", renewDeadline=" + getRenewDeadline() + ", retryPeriod=" + getRetryPeriod()
            + ", closeTimeout=" + getCloseTimeout() + ", lockType=" + lockType + "]";
    }
}
