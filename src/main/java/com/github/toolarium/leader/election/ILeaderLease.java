/*
 * ILeaderLease.java
 *
 * Copyright by toolarium, all rights reserved.
 */
package com.github.toolarium.leader.election;

import com.github.toolarium.leader.election.dto.ILeaderElectionConfiguration;
import com.github.toolarium.leader.election.dto.ILeaderElectionInformation;
import com.github.toolarium.leader.election.exception.LeaderElectionException;
import java.time.Duration;
import java.time.Instant;


/**
 * A fencing token passed to {@link ILeaderTask#run(ILeaderLease)} that lets the task
 * verify whether this node is still the elected leader while it executes.
 * Provides read-only access to the elector identity, election information, configuration,
 * and the remaining lead time so long-running tasks can detect when they are approaching
 * the renew deadline and react (checkpoint, clean stop, etc.) before leadership is lost.
 *
 * @author patrick
 */
public interface ILeaderLease {

    /**
     * Returns the unique identifier of the leader elector that holds this lease.
     *
     * @return the elector id; never {@code null}
     */
    String getId();


    /**
     * Returns the leader election information (namespace, name, identity) for this elector.
     *
     * @return the leader election information; never {@code null}
     */
    ILeaderElectionInformation getLeaderElectionInformation();


    /**
     * Returns the leader election configuration (timeouts, retry period, …) for this elector.
     *
     * @return the leader election configuration; never {@code null}
     */
    ILeaderElectionConfiguration getLeaderElectionConfiguration();


    /**
     * Returns the instant the leadership lease was last confirmed (acquired or successfully
     * extended), or {@code null} if not yet available.
     * Use this together with {@link #getRemainingLeadTime()} to detect when a task is approaching
     * the renew deadline.
     *
     * @return the instant leadership was last confirmed, or {@code null}
     */
    Instant getLeaderSince();


    /**
     * Returns the time remaining before the renew deadline is reached since this node became
     * the leader. A positive value means the lease is still well within the deadline; a zero
     * or negative value means the renew deadline has been exceeded and another node may take
     * over after the full timeout elapses.
     * Returns {@link Duration#ZERO} if the leadership timestamp is not yet available.
     *
     * @return the remaining lead time; never {@code null}
     */
    default Duration getRemainingLeadTime() {
        Instant since = getLeaderSince();
        if (since == null) {
            return Duration.ZERO;
        }
        Duration elapsed = Duration.between(since, Instant.now());
        Duration renewDeadline = getLeaderElectionConfiguration().getRenewDeadline();
        return renewDeadline.minus(elapsed);
    }


    /**
     * Returns {@code true} if the remaining lead time is less than {@code threshold}.
     * Use this to detect when a long-running task is approaching the renew deadline so it
     * can checkpoint and stop cleanly before leadership is lost.
     *
     * @param threshold the warning threshold; must not be {@code null}
     * @return true if the remaining lead time is below the threshold
     */
    default boolean isExpiringSoon(Duration threshold) {
        return getRemainingLeadTime().compareTo(threshold) < 0;
    }


    /**
     * Attempts to extend the leadership lease, resetting the renew deadline timer.
     * Use this when a long-running task needs more time before the renew deadline is reached.
     * After a successful extension {@link #getRemainingLeadTime()} resets to the full
     * {@code renewDeadline} window, giving the task another full cycle.
     * An extension can fail if leadership was already lost to another node.
     *
     * @return {@code true} if the lease was successfully extended and this node is still the leader,
     *         {@code false} if the extension failed or leadership was already lost
     * @throws LeaderElectionException if the extension attempt encounters an unrecoverable error
     */
    boolean tryExtend() throws LeaderElectionException;


    /**
     * Returns {@code true} if this node is still the elected leader.
     *
     * @return true if still the leader, false otherwise
     */
    boolean isValid();


    /**
     * Asserts that this node is still the elected leader.
     *
     * @throws LeaderElectionException if this node is no longer the leader
     */
    default void verify() throws LeaderElectionException {
        if (!isValid()) {
            throw new LeaderElectionException("Leadership lost for [" + getLeaderElectionInformation().getUniqueName() + "]");
        }
    }
}
