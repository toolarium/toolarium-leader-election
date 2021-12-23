/*
 * LeaderElectionFactoryTest.java
 *
 * Copyright by toolarium, all rights reserved.
 */
package com.github.toolarium.leader.election;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.github.toolarium.leader.election.dto.LeaderElectionConfiguration;
import com.github.toolarium.leader.election.dto.LeaderElectionInformation;
import java.io.IOException;
import org.junit.jupiter.api.Test;

/**
 * 
 * @author patrick
 */
public class LeaderElectionFactoryTest {

    /**
     * Test the jgroup
     *
     * @throws IOException In case of an i/o error
     * @throws InterruptedException In case of an interruption
     */
    @Test
    public void testJGroup() throws IOException, InterruptedException {
        
        ILeaderElector el = LeaderElectionFactory.getInstance().getLeaderElection(new LeaderElectionInformation("namespace", "name", "test"), new LeaderElectionConfiguration(2));

        assertFalse(el.isLeader());
        Thread.sleep(200);

        assertTrue(el.isLeader());
        Thread.sleep(2000);
        assertTrue(el.isLeader());
        Thread.sleep(2000);
        assertTrue(el.isLeader());
        
    }
}
