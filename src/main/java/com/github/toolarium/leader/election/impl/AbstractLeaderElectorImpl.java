/*
 * AbstractLeaderElectorImpl.java
 *
 * Copyright by toolarium, all rights reserved.
 */
package com.github.toolarium.leader.election.impl;

import com.github.toolarium.leader.election.ILeaderElector;
import com.github.toolarium.leader.election.dto.LeaderElectionConfiguration;
import com.github.toolarium.leader.election.dto.LeaderElectionInformation;
import java.io.IOException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


/**
 * Base class for leader elector implementations
 * 
 * @author patrick
 */
public abstract class AbstractLeaderElectorImpl implements ILeaderElector {
    private static final Logger LOG = LoggerFactory.getLogger(AbstractLeaderElectorImpl.class);
    private LeaderElectionInformation leaderElectionInformation;
    private LeaderElectionConfiguration leaderElectionConfiguration;
    private final String uniqueName;
    private volatile Boolean isLeader;

    
    /**
     * Constructor for AbstractLeaderElectorImpl
     * 
     * @param leaderElectionInformation the leader election information
     * @param leaderElectionConfiguration the leader election configuration
     * @throws IOException in case of an i/o error
     */
    public AbstractLeaderElectorImpl(LeaderElectionInformation leaderElectionInformation, LeaderElectionConfiguration leaderElectionConfiguration) 
            throws IOException {
        this.leaderElectionInformation = leaderElectionInformation;
        this.leaderElectionConfiguration = leaderElectionConfiguration;
        this.uniqueName = leaderElectionInformation.getUniqueName();
        isLeader = null;
        
        init();
    }

    
    /**
     * @see com.github.toolarium.leader.election.ILeaderElector#isLeader()
     */
    @Override
    public boolean isLeader() {
        if ((isLeader != null) && isLeader.booleanValue()) {
            LOG.debug("In lead of [" + getUniqueName() + "].");
            return true;
        }
        
        LOG.debug("New leader found for [" + getUniqueName() + "].");
        return false;
    }

    
    /**
     * Initialize
     * 
     * @throws IOException in case of an i/o error
     */
    protected abstract void init() throws IOException;

    
    /**
     * Set the leader
     *
     * @param isLeader the leader
     * @param inputDescription the description
     */
    protected void setLeader(final Boolean isLeader, final String inputDescription) {
        final String description;
        if (inputDescription != null) {
            description = "(" + inputDescription + ")"; 
        } else {
            description = "";
        }
        
        if (isLeader == null) {
            if (this.isLeader != null && this.isLeader) {
                LOG.debug("Losed lead of [" + getUniqueName() + "].");
            }            
        } else if (isLeader.booleanValue()) {
            if (this.isLeader == null || !this.isLeader) {
                LOG.debug("Get in lead of [" + getUniqueName() + "].");
            }
        } else {
            if (this.isLeader == null) {
                LOG.debug("New lead found for [" + getUniqueName() + "] " + description + ".");
            } else if (this.isLeader) {
                LOG.debug("New lead found for [" + getUniqueName() + "] " + description + ".");
            }
        }        
        
        this.isLeader = isLeader;
    }


    /**
     * Get the unique name
     *
     * @return the unique name
     */
    protected String getUniqueName() {
        return uniqueName;
    }
    
    
    /**
     * Get the leader election information
     *
     * @return the leader election information
     */
    protected LeaderElectionInformation getLeaderElectionInformation() {
        return leaderElectionInformation;
    }


    /**
     * Get the leader election information
     *
     * @return the leader election information
     */
    protected LeaderElectionConfiguration getLeaderElectionConfiguration() {
        return leaderElectionConfiguration;
    }
}
