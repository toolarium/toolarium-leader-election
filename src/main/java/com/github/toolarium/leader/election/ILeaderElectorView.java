/*
 * ILeaderElectorView.java
 *
 * Copyright by toolarium, all rights reserved.
 */
package com.github.toolarium.leader.election;

import com.github.toolarium.leader.election.dto.ILeaderElectionConfiguration;
import com.github.toolarium.leader.election.dto.ILeaderElectionInformation;
import java.time.Duration;
import java.time.Instant;


/**
 * Read-only administration view of a leader elector that currently holds leadership.
 * Provides access to its identity, configuration, and the instant leadership was acquired.
 * Obtain instances via {@link ILeaderElectionAdministration#getElectors()}.
 *
 * @author patrick
 */
public interface ILeaderElectorView {

    /**
     * Returns the unique identifier of this leader elector.
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
     * Returns the instant the leadership lease was last confirmed.
     *
     * @return the last confirmed leadership instant; never {@code null}
     */
    Instant getLeaderElectorTimestamp();


    /**
     * Returns the time remaining before the renew deadline is reached since this node became
     * the leader. A positive value means the lease is still well within the deadline.
     * Returns {@link Duration#ZERO} if the leadership timestamp is not yet available.
     *
     * @return the remaining lead time; never {@code null}
     */
    default Duration getRemainingLeadTime() {
        Instant since = getLeaderElectorTimestamp();
        if (since == null) {
            return Duration.ZERO;
        }
        Duration elapsed = Duration.between(since, Instant.now());
        return getLeaderElectionConfiguration().getRenewDeadline().minus(elapsed);
    }
}
