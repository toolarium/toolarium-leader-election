/*
 * NetworkLeaderElectotionTest.java
 *
 * Copyright by toolarium, all rights reserved.
 */
package com.github.toolarium.leader.election.impl.network;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.github.toolarium.leader.election.AbstractLeaderElectorTest;
import com.github.toolarium.leader.election.ILeaderElector;
import com.github.toolarium.leader.election.LeaderElectionFactory;
import com.github.toolarium.leader.election.dto.LeaderElectionConfiguration;
import com.github.toolarium.leader.election.dto.LeaderElectionInformation;
import com.github.toolarium.leader.election.dto.LeaderElectionStrategy;
import java.time.Duration;
import org.junit.jupiter.api.Test;


/**
 * Implements leader election tests using the network strategy.
 *
 * @author patrick
 */
public class NetworkLeaderElectotionTest extends AbstractLeaderElectorTest {


    /**
     * @see com.github.toolarium.leader.election.AbstractLeaderElectorTest#getInitWaitMillis()
     */
    @Override
    protected long getInitWaitMillis() {
        return 8000L;
    }


    /**
     * @see com.github.toolarium.leader.election.AbstractLeaderElectorTest#getFailoverWaitMillis()
     */
    @Override
    protected long getFailoverWaitMillis() {
        return 12000L;
    }


    /**
     * @see com.github.toolarium.leader.election.AbstractLeaderElectorTest#createLeaderElector(java.lang.String)
     */
    @Override
    protected ILeaderElector createLeaderElector(String name) throws Exception {
        return LeaderElectionFactory.getInstance().getLeaderElection(
                LeaderElectionStrategy.NETWORK, new LeaderElectionInformation(name), new LeaderElectionConfiguration(10));
    }


    /**
     * Overrides the base test to insert a stabilisation pause between the two lifecycles.
     * After a JGroups channel is closed, the underlying UDP socket and cluster membership
     * state need a moment to settle before a new channel connects to the same cluster name.
     * Without the pause, the second {@code elect()} can race with residual JGroups state
     * and time out before the phantom-member stabilisation window (retryPeriod + 2 s) elapses.
     *
     * @throws Exception In case of an error
     */
    @Test
    @Override
    public void testReuseAfterClose() throws Exception {
        ILeaderElector elector = createLeaderElector("testReuseAfterClose");

        // First lifecycle
        assertTrue(elector.elect(Duration.ofMillis(getInitWaitMillis())), "Must become leader on first elect()");
        assertTrue(elector.isLeader());
        elector.close();
        assertFalse(elector.isLeader(), "Must not be leader after close()");

        // Allow JGroups to fully process the departure before reconnecting to the same cluster name.
        Thread.sleep(2000L);

        // Second lifecycle using the same elector instance
        assertTrue(elector.elect(Duration.ofMillis(getInitWaitMillis())), "Must become leader again after close()");
        assertTrue(elector.isLeader());
        elector.close();
        assertFalse(elector.isLeader());
    }
}
