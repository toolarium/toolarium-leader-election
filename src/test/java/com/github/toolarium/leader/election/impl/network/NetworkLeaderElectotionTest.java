/*
 * NetworkLeaderElectotionTest.java
 *
 * Copyright by toolarium, all rights reserved.
 */
package com.github.toolarium.leader.election.impl.network;

import com.github.toolarium.leader.election.AbstractLeaderElectorTest;
import com.github.toolarium.leader.election.ILeaderElector;
import com.github.toolarium.leader.election.LeaderElectionFactory;
import com.github.toolarium.leader.election.dto.LeaderElectionConfiguration;
import com.github.toolarium.leader.election.dto.LeaderElectionInformation;
import com.github.toolarium.leader.election.dto.LeaderElectionStrategy;


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
}
