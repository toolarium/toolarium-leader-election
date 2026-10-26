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
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.jgroups.Address;
import org.jgroups.BytesMessage;
import org.jgroups.Global;
import org.jgroups.JChannel;
import org.jgroups.Message;
import org.jgroups.View;
import org.jgroups.conf.ConfiguratorFactory;
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
     * Only an answer to a challenge this node sent within the renew deadline confirms the lead: an answer to an older
     * challenge (buffered during a JVM pause, or recorded and replayed) or to an unknown one does not count.
     *
     * @throws Exception in case of an error
     */
    @Test
    public void testReplayedHeartbeatIsIgnored() throws Exception {
        NetworkLeaderElectorImpl elector = createElector(1, Duration.ofMillis(2000), Duration.ofMillis(1000), Duration.ofMillis(500));
        Address self = UUID.randomUUID();
        Address other = UUID.randomUUID();
        try {
            long challenge = elector.issueChallenge();
            assertTrue(elector.recordHeartbeat(other, challenge), "The answer to a current challenge counts");
            elector.applyView(View.create(self, 1, self, other), self);
            assertTrue(elector.isLeader());

            Thread.sleep(1300);
            assertFalse(elector.recordHeartbeat(other, challenge), "An answer to a challenge older than the renew deadline must not count");
            assertFalse(elector.recordHeartbeat(other, challenge + 1), "An answer to an unknown challenge must not count");
            elector.applyView(View.create(self, 1, self, other), self);
            assertFalse(elector.isLeader(), "Old answers must not keep the lead");

            assertTrue(elector.recordHeartbeat(other, elector.issueChallenge()), "The answer to a new challenge counts");
            elector.applyView(View.create(self, 1, self, other), self);
            assertTrue(elector.isLeader());
        } finally {
            elector.close();
        }
    }


    /**
     * With a quorum of 3 the coordinator needs the answers of two other members: one answer does not confirm the lead.
     *
     * @throws Exception in case of an error
     */
    @Test
    public void testQuorumNeedsEnoughAnswers() throws Exception {
        NetworkLeaderElectorImpl elector = createElector(3, Duration.ofSeconds(15), Duration.ofSeconds(10), Duration.ofSeconds(5));
        Address self = UUID.randomUUID();
        Address second = UUID.randomUUID();
        Address third = UUID.randomUUID();
        try {
            long challenge = elector.issueChallenge();
            elector.recordHeartbeat(second, challenge);
            elector.applyView(View.create(self, 1, self, second, third), self);
            assertFalse(elector.isLeader(), "One answer must not confirm the lead with a quorum of 3");

            elector.recordHeartbeat(third, challenge);
            elector.applyView(View.create(self, 1, self, second, third), self);
            assertTrue(elector.isLeader(), "Two answers confirm the lead with a quorum of 3");
        } finally {
            elector.close();
        }
    }


    /**
     * The lead counts from the time the answered challenge was sent, not from the time the answer was processed: it cannot
     * last longer than the renew deadline after the members confirmed it.
     *
     * @throws Exception in case of an error
     */
    @Test
    public void testLeadCountsFromChallengeSendTime() throws Exception {
        NetworkLeaderElectorImpl elector = createElector(1, Duration.ofMillis(2000), Duration.ofMillis(1000), Duration.ofMillis(500));
        Address self = UUID.randomUUID();
        Address other = UUID.randomUUID();
        try {
            long challenge = elector.issueChallenge();
            Thread.sleep(400);
            assertTrue(elector.recordHeartbeat(other, challenge));
            elector.applyView(View.create(self, 1, self, other), self);
            assertTrue(elector.isLeader());
            assertTrue(elector.getRemainingLeadTime().toMillis() <= 650, "The lead must count from the send time of the challenge, remaining "
                       + elector.getRemainingLeadTime().toMillis() + " ms");
        } finally {
            elector.close();
        }
    }


    /**
     * Malformed messages do not confirm the lead; an answer to a current challenge does. A view of a channel that is not the
     * current one is not applied.
     *
     * @throws Exception in case of an error
     */
    @Test
    public void testMessageHandling() throws Exception {
        NetworkLeaderElectorImpl elector = createElector(1, Duration.ofSeconds(15), Duration.ofSeconds(10), Duration.ofSeconds(5));
        Address self = UUID.randomUUID();
        Address other = UUID.randomUUID();
        try (JChannel channel = new JChannel(ConfiguratorFactory.getStackConfigurator(Global.DEFAULT_PROTOCOL_STACK))) {
            long challenge = elector.issueChallenge();
            elector.handleMessage(channel, message(other, ByteBuffer.allocate(4).put(new byte[] {'H', 'B', 0, 0}).array()));
            elector.handleMessage(channel, message(other, ByteBuffer.allocate(10).put(new byte[] {'X', 'X'}).putLong(challenge).array()));
            elector.handleMessage(channel, message(other, ByteBuffer.allocate(10).put(new byte[] {'H', 'B'}).putLong(challenge + 1).array()));
            elector.applyView(View.create(self, 1, self, other), self);
            assertFalse(elector.isLeader(), "Malformed messages and unknown challenges must not confirm the lead");

            elector.handleMessage(channel, message(other, ByteBuffer.allocate(10).put(new byte[] {'H', 'B'}).putLong(challenge).array()));
            elector.applyView(View.create(self, 2, self, other), self);
            assertTrue(elector.isLeader(), "The answer to a current challenge confirms the lead");

            assertFalse(elector.applyView(channel, View.create(self, 3, self, other)), "A view of another channel must not be applied");
        } finally {
            elector.close();
        }
    }


    /**
     * Create a message from a member.
     *
     * @param source the sender
     * @param payload the payload
     * @return the message
     */
    private static Message message(Address source, byte[] payload) {
        return new BytesMessage(null, payload).setSrc(source);
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
     * A lead lost only because it was not confirmed starts a new term when it is confirmed again: a lease of the old term
     * stays invalid, since another node may have led in between.
     *
     * @throws Exception in case of an error
     */
    @Test
    public void testFencedLeadStartsNewTerm() throws Exception {
        NetworkLeaderElectorImpl elector = createElector(1, Duration.ofMillis(2000), Duration.ofMillis(1000), Duration.ofMillis(500));
        Address self = UUID.randomUUID();
        Address other = UUID.randomUUID();
        CountDownLatch leaseCaptured = new CountDownLatch(1);
        CountDownLatch checkLease = new CountDownLatch(1);
        AtomicBoolean validAfterNewTerm = new AtomicBoolean(true);
        Thread taskThread = null;
        try {
            elector.recordHeartbeat(other, elector.issueChallenge());
            elector.applyView(View.create(self, 1, self, other), self);
            assertTrue(elector.isLeader());
            taskThread = new Thread(() -> {
                try {
                    elector.runIfLeader("fenced-lead-task", lease -> {
                        leaseCaptured.countDown();
                        // ignores the interrupt of the lease monitor to check the lease afterwards
                        boolean released = false;
                        while (!released) {
                            try {
                                released = checkLease.await(10, TimeUnit.SECONDS);
                            } catch (InterruptedException e) {
                                // keep waiting
                            }
                        }
                        validAfterNewTerm.set(lease.isValid());
                    });
                } catch (Exception e) {
                    // NOP
                }
            }, "test-fenced-lead");
            taskThread.start();
            assertTrue(leaseCaptured.await(5, TimeUnit.SECONDS));

            // no heartbeat within the renew deadline: the lead is fenced, the next check reports the loss
            Thread.sleep(1300);
            elector.applyView(View.create(self, 1, self, other), self);
            assertFalse(elector.isLeader());

            // confirmed again
            elector.recordHeartbeat(other, elector.issueChallenge());
            elector.applyView(View.create(self, 1, self, other), self);
            assertTrue(elector.isLeader());

            checkLease.countDown();
            taskThread.join(5000);
            assertFalse(validAfterNewTerm.get(), "A lease of the fenced term must not become valid again");
        } finally {
            checkLease.countDown();
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
