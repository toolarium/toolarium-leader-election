/*
 * MyLibrary.java
 *
 * Copyright by toolarium, all rights reserved.
 */

package com.github.toolarium.leader.election;

import com.github.toolarium.leader.election.dto.LeaderElectionConfiguration;
import com.github.toolarium.leader.election.dto.LeaderElectionInformation;
import com.github.toolarium.leader.election.impl.jgroup.JGroupLeaderElectorImpl;
import com.github.toolarium.leader.election.impl.kubernetes.KubernetesLeaderElectorImpl;
import com.github.toolarium.leader.election.impl.kubernetes.KubernetesUtil;
import java.io.IOException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


/**
 * Defines the leader election factory.
 * 
 * @author patrick
 */
public final class LeaderElectionFactory {
    private static final Logger LOG = LoggerFactory.getLogger(LeaderElectionFactory.class);


    /**
     * Private class, the only instance of the singelton which will be created by accessing the holder class.
     *
     * @author Patrick Meier
     */
    private static class HOLDER {
        static final LeaderElectionFactory INSTANCE = new LeaderElectionFactory();
    }

    /**
     * Constructor
     */
    private LeaderElectionFactory() {
        // NOP
    }

    /**
     * Get the instance
     *
     * @return the instance
     */
    public static LeaderElectionFactory getInstance() {
        return HOLDER.INSTANCE;
    }

    
    /**
     * Get the leader electior
     *
     * @param leaderElectionInformation the leader election information
     * @return the leader elector
     * @throws IOException in case of an i/o error
     */
    public ILeaderElector getLeaderElection(LeaderElectionInformation leaderElectionInformation) throws IOException {
        return getLeaderElection(leaderElectionInformation, new LeaderElectionConfiguration());
    }

    
    /**
     * Get the leader electior
     *
     * @param leaderElectionInformation the leader election information
     * @param leaderElectionConfiguration the leader election configuration
     * @return the leader elector
     * @throws IOException in case of an i/o error
     */
    public ILeaderElector getLeaderElection(LeaderElectionInformation leaderElectionInformation, LeaderElectionConfiguration leaderElectionConfiguration) throws IOException {
        ILeaderElector leaderElector = null;
        
        if (KubernetesUtil.getInstance().isAvailable(leaderElectionInformation)) {
            try {
                leaderElector = new KubernetesLeaderElectorImpl(leaderElectionInformation, leaderElectionConfiguration);
                LOG.info("Use kubernetes leader elector.");
            } catch (IOException e) {
                LOG.info("Could not initialize kubernetes leader elector: " + e.getMessage());
            }
        }

        if (leaderElector == null) {
            LOG.info("Use jgroup leader elector.");
            leaderElector = new JGroupLeaderElectorImpl(leaderElectionInformation, leaderElectionConfiguration);
        }
        
        return leaderElector;
    }
}
