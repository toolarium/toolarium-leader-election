/*
 * NetworkQuorumTest.java
 *
 * Copyright by toolarium, all rights reserved.
 */
package com.github.toolarium.leader.election.impl.network;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.github.toolarium.leader.election.ILeaderElector;
import com.github.toolarium.leader.election.LeaderElectionFactory;
import com.github.toolarium.leader.election.dto.LeaderElectionInformation;
import com.github.toolarium.leader.election.dto.network.NetworkLeaderElectionConfiguration;
import java.time.Duration;
import org.jgroups.Address;
import org.jgroups.View;
import org.jgroups.util.UUID;
import org.junit.jupiter.api.Test;


/**
 * Tests the NETWORK quorum and the time-based fencing of the lead, without a JGroups channel.
 *
 * @author patrick
 */
public class NetworkQuorumTest {

    /**
     * Without a quorum the coordinator of a single-member view leads, a non-coordinator never does.
     *
     * @throws Exception in case of an error
     */
    @Test
    public void testWithoutQuorumCoordinatorLeads() throws Exception {
        NetworkLeaderElectorImpl elector = createElector(1, Duration.ofSeconds(15), Duration.ofSeconds(10), Duration.ofSeconds(5));
        Address self = UUID.randomUUID();
        Address other = UUID.randomUUID();
        try {
            elector.applyView(View.create(self, 1, self), self);
            assertTrue(elector.isLeader());

            elector.applyView(View.create(other, 2, other, self), self);
            assertFalse(elector.isLeader());
        } finally {
            elector.close();
        }
    }


    /**
     * With a quorum the coordinator of a minority partition does not lead; it takes the lead as soon as the view reaches the quorum
     * and loses it when the view drops below.
     *
     * @throws Exception in case of an error
     */
    @Test
    public void testMinorityPartitionHasNoLeader() throws Exception {
        NetworkLeaderElectorImpl elector = createElector(2, Duration.ofSeconds(15), Duration.ofSeconds(10), Duration.ofSeconds(5));
        Address self = UUID.randomUUID();
        Address other = UUID.randomUUID();
        try {
            elector.applyView(View.create(self, 1, self), self);
            assertFalse(elector.isLeader(), "A single member is below the quorum of 2");

            elector.recordHeartbeat(other);
            elector.applyView(View.create(self, 2, self, other), self);
            assertTrue(elector.isLeader(), "The coordinator of a view with quorum leads");

            elector.applyView(View.create(self, 3, self), self);
            assertFalse(elector.isLeader(), "The lead is lost when the view drops below the quorum");
        } finally {
            elector.close();
        }
    }


    /**
     * A coordinator whose members send no heartbeats, e.g. with a stale view after a long pause while the others elected a new
     * coordinator, does not confirm its lead; heartbeats confirm it, and the lead expires when they stop.
     *
     * @throws Exception in case of an error
     */
    @Test
    public void testCoordinatorNeedsHeartbeats() throws Exception {
        NetworkLeaderElectorImpl elector = createElector(1, Duration.ofMillis(2000), Duration.ofMillis(1000), Duration.ofMillis(500));
        Address self = UUID.randomUUID();
        Address other = UUID.randomUUID();
        try {
            elector.applyView(View.create(self, 1, self, other), self);
            assertFalse(elector.isLeader(), "Without a heartbeat of the other member the lead is not confirmed");

            elector.recordHeartbeat(other);
            elector.applyView(View.create(self, 1, self, other), self);
            assertTrue(elector.isLeader(), "A heartbeat of the other member confirms the lead");

            Thread.sleep(1300);
            elector.applyView(View.create(self, 1, self, other), self);
            assertFalse(elector.isLeader(), "Without new heartbeats the lead expires after the renew deadline");
        } finally {
            elector.close();
        }
    }


    /**
     * A view older than the last applied one is ignored.
     *
     * @throws Exception in case of an error
     */
    @Test
    public void testOlderViewIsIgnored() throws Exception {
        NetworkLeaderElectorImpl elector = createElector(1, Duration.ofSeconds(15), Duration.ofSeconds(10), Duration.ofSeconds(5));
        Address self = UUID.randomUUID();
        Address other = UUID.randomUUID();
        try {
            elector.applyView(View.create(other, 5, other, self), self);
            assertFalse(elector.isLeader());

            // an older view in which this node was the coordinator must not give it the lead back
            elector.applyView(View.create(self, 4, self), self);
            assertFalse(elector.isLeader(), "An older view must be ignored");
        } finally {
            elector.close();
        }
    }


    /**
     * The lead expires when it was not confirmed by a cluster view within the renew deadline and is valid again after the next confirmation.
     *
     * @throws Exception in case of an error
     */
    @Test
    public void testLeadExpiresWithoutConfirmation() throws Exception {
        NetworkLeaderElectorImpl elector = createElector(1, Duration.ofMillis(2000), Duration.ofMillis(1000), Duration.ofMillis(500));
        Address self = UUID.randomUUID();
        try {
            elector.applyView(View.create(self, 1, self), self);
            assertTrue(elector.isLeader());

            Thread.sleep(1300);
            assertFalse(elector.isLeader(), "The lead must expire after the renew deadline without confirmation");

            elector.applyView(View.create(self, 1, self), self);
            assertTrue(elector.isLeader(), "A new confirmation restores the lead");
        } finally {
            elector.close();
        }
    }


    /**
     * The minimum number of members is validated and part of equals.
     */
    @Test
    public void testConfiguration() {
        NetworkLeaderElectionConfiguration config = new NetworkLeaderElectionConfiguration();
        assertEquals(NetworkLeaderElectionConfiguration.DEFAULT_MINIMUM_MEMBERS, config.getMinimumMembers());
        assertThrows(IllegalArgumentException.class, () -> config.setMinimumMembers(0));

        NetworkLeaderElectionConfiguration quorum = new NetworkLeaderElectionConfiguration();
        quorum.setMinimumMembers(3);
        assertNotEquals(config, quorum);
        assertEquals(quorum, new NetworkLeaderElectionConfiguration(3, Duration.ofSeconds(15), Duration.ofSeconds(10), Duration.ofSeconds(5)));
        assertTrue(quorum.toString().contains("minimumMembers=3"));
    }


    /**
     * The builder passes the minimum number of members and the timings to the elector.
     *
     * @throws Exception in case of an error
     */
    @Test
    public void testBuilderSetsMinimumMembers() throws Exception {
        ILeaderElector elector = LeaderElectionFactory.builder().name("network-quorum-builder").timeout(Duration.ofSeconds(30)).network().minimumMembers(2).build();
        try {
            NetworkLeaderElectionConfiguration config = ((NetworkLeaderElectorImpl) elector).getLeaderElectionConfiguration();
            assertEquals(2, config.getMinimumMembers());
            assertEquals(Duration.ofSeconds(30), config.getTimeout());
        } finally {
            elector.close();
        }
    }


    /**
     * Create a network elector without connecting a channel.
     *
     * @param minimumMembers the minimum number of members
     * @param timeout the timeout
     * @param renewDeadline the renew deadline
     * @param retryPeriod the retry period
     * @return the elector
     */
    private NetworkLeaderElectorImpl createElector(int minimumMembers, Duration timeout, Duration renewDeadline, Duration retryPeriod) {
        return new NetworkLeaderElectorImpl(new LeaderElectionInformation("network-quorum-" + System.nanoTime()),
                new NetworkLeaderElectionConfiguration(minimumMembers, timeout, renewDeadline, retryPeriod));
    }
}
