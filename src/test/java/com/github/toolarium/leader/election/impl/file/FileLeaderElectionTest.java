/*
 * FileLeaderElectionTest.java
 *
 * Copyright by toolarium, all rights reserved.
 */
package com.github.toolarium.leader.election.impl.file;

import com.github.toolarium.leader.election.AbstractLeaderElectorTest;
import com.github.toolarium.leader.election.ILeaderElector;
import com.github.toolarium.leader.election.LeaderElectionFactory;
import com.github.toolarium.leader.election.dto.LeaderElectionConfiguration;
import com.github.toolarium.leader.election.dto.LeaderElectionInformation;
import com.github.toolarium.leader.election.dto.LeaderElectionStrategy;


/**
 * Implements leader election tests using the file-system strategy.
 *
 * @author patrick
 */
public class FileLeaderElectionTest extends AbstractLeaderElectorTest {


    /**
     * @see com.github.toolarium.leader.election.AbstractLeaderElectorTest#createLeaderElector(java.lang.String)
     */
    @Override
    protected ILeaderElector createLeaderElector(String name) throws Exception {
        return LeaderElectionFactory.getInstance().getLeaderElection(
                LeaderElectionStrategy.FILE, new LeaderElectionInformation(name), new LeaderElectionConfiguration(2));
    }
}
