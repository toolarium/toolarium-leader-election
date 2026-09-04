/*
 * LeaderElectionFactory.java
 *
 * Copyright by toolarium, all rights reserved.
 */

package com.github.toolarium.leader.election;

import com.github.toolarium.leader.election.dto.LeaderElectionConfiguration;
import com.github.toolarium.leader.election.dto.LeaderElectionInformation;
import com.github.toolarium.leader.election.dto.db.DatabaseLeaderElectionConfiguration;
import com.github.toolarium.leader.election.dto.file.FileLeaderElectionConfiguration;
import com.github.toolarium.leader.election.exception.LeaderElectionException;
import com.github.toolarium.leader.election.impl.LeaderElectionScheduler;
import com.github.toolarium.leader.election.impl.database.DatabaseLeaderElectorImpl;
import com.github.toolarium.leader.election.impl.file.FileLeaderElectorImpl;
import com.github.toolarium.leader.election.impl.kubernetes.KubernetesLeaderElectorImpl;
import com.github.toolarium.leader.election.impl.kubernetes.KubernetesUtil;
import com.github.toolarium.leader.election.impl.network.NetworkLeaderElectorImpl;
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
    private static final class HOLDER {
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
     * Get the leader elector
     *
     * @param leaderElectionInformation the leader election information
     * @return the leader elector
     * @throws LeaderElectionException In case of an initialisation error
     */
    public LeaderElector getLeaderElection(LeaderElectionInformation leaderElectionInformation) throws LeaderElectionException {
        return getLeaderElection(leaderElectionInformation, new LeaderElectionConfiguration());
    }

    
    /**
     * Get the leader elector
     *
     * @param leaderElectionStrategy the leader election strategy. To choose automated put in null. 
     * @param leaderElectionInformation the leader election information
     * @return the leader elector
     * @throws LeaderElectionException In case of an initialisation error
     */
    public LeaderElector getLeaderElection(LeaderElectionStrategy leaderElectionStrategy, LeaderElectionInformation leaderElectionInformation) throws LeaderElectionException {
        return getLeaderElection(leaderElectionStrategy, leaderElectionInformation, new LeaderElectionConfiguration());
    }

    
    /**
     * Get the leader elector
     *
     * @param leaderElectionInformation the leader election information
     * @param leaderElectionConfiguration the leader election configuration
     * @return the leader elector
     * @throws LeaderElectionException In case of an initialisation error
     */
    public LeaderElector getLeaderElection(LeaderElectionInformation leaderElectionInformation, LeaderElectionConfiguration leaderElectionConfiguration) throws LeaderElectionException {
        return getLeaderElection(null, leaderElectionInformation, leaderElectionConfiguration);
    }

    
    /**
     * Get the leader elector
     *
     * @param inputLeaderElectionStrategy the leader election strategy. To choose automated put in null. 
     * @param leaderElectionInformation the leader election information
     * @param leaderElectionConfiguration the leader election configuration
     * @return the leader elector
     * @throws LeaderElectionException In case of an initialisation error
     */
    public LeaderElector getLeaderElection(LeaderElectionStrategy inputLeaderElectionStrategy, LeaderElectionInformation leaderElectionInformation, LeaderElectionConfiguration leaderElectionConfiguration) throws LeaderElectionException {
        LeaderElectionStrategy leaderElectionStrategy = inputLeaderElectionStrategy;
        if (leaderElectionStrategy == null) {
            if (KubernetesUtil.getInstance().isAvailable(leaderElectionInformation)) {
                leaderElectionStrategy = LeaderElectionStrategy.KUBERNETES;
            } else {
                leaderElectionStrategy = LeaderElectionStrategy.NETWORK;
            }
            LOG.info("Auto-selected leader election strategy: {}.", leaderElectionStrategy);
        }

        return selectLeaderElectorBasedOnStrategy(leaderElectionStrategy, leaderElectionInformation, leaderElectionConfiguration);
    }

    
    /**
     * Select the leader elector based on the given strategy.
     *
     * @param leaderElectionStrategy the leader election strategy. To choose automated put in null.
     * @param leaderElectionInformation the leader election information
     * @param leaderElectionConfiguration the leader election configuration
     * @return the leader elector
     * @throws LeaderElectionException In case of an initialisation error
     */
    private LeaderElector selectLeaderElectorBasedOnStrategy(LeaderElectionStrategy leaderElectionStrategy, LeaderElectionInformation leaderElectionInformation, LeaderElectionConfiguration leaderElectionConfiguration) throws LeaderElectionException {
        LeaderElector leaderElector = null;
        switch (leaderElectionStrategy) {
            case FILE:
                LOG.info("Use file system leader elector.");
                FileLeaderElectionConfiguration fileLeaderElectionConfiguration = null;
                if (leaderElectionConfiguration instanceof FileLeaderElectionConfiguration) {
                    fileLeaderElectionConfiguration = (FileLeaderElectionConfiguration)leaderElectionConfiguration;
                } else {
                    fileLeaderElectionConfiguration = new FileLeaderElectionConfiguration(leaderElectionConfiguration.getTimeout(), leaderElectionConfiguration.getRenewDeadline(), leaderElectionConfiguration.getRetryPeriod());
                }
                
                leaderElector = new FileLeaderElectorImpl(leaderElectionInformation, fileLeaderElectionConfiguration);
                break;
                
            case DATABASE:
                if (leaderElectionConfiguration instanceof DatabaseLeaderElectionConfiguration) {
                    LOG.info("Use database leader elector.");
                    leaderElector = new DatabaseLeaderElectorImpl(leaderElectionInformation, (DatabaseLeaderElectionConfiguration)leaderElectionConfiguration);
                } else {
                    throw new LeaderElectionException("Invalid configuration. To use database leader election you must define a " + DatabaseLeaderElectionConfiguration.class.getName() + "!");
                }
                break;

            case NETWORK:
                LOG.info("Use network leader elector.");
                leaderElector = new NetworkLeaderElectorImpl(leaderElectionInformation, leaderElectionConfiguration);
                break;

            case KUBERNETES:
                LOG.info("Use kubernetes leader elector.");
                leaderElector = new KubernetesLeaderElectorImpl(leaderElectionInformation, leaderElectionConfiguration);
                break;
                
            default:
                break;            
        }
        
        if (leaderElector != null) {
            LeaderElectionScheduler.getInstance().register(leaderElector);
        }

        return leaderElector;
    }
}
