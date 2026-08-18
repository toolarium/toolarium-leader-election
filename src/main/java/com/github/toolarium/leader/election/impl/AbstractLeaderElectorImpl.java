/*
 * AbstractLeaderElectorImpl.java
 *
 * Copyright by toolarium, all rights reserved.
 */
package com.github.toolarium.leader.election.impl;

import com.github.toolarium.leader.election.LeaderElector;
import com.github.toolarium.leader.election.dto.LeaderElectionConfiguration;
import com.github.toolarium.leader.election.dto.LeaderElectionInformation;
import com.github.toolarium.leader.election.exception.LeaderElectionException;
import java.time.Instant;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


/**
 * Base class for leader elector implementations
 * 
 * @author patrick
 */
public abstract class AbstractLeaderElectorImpl<T extends LeaderElectionConfiguration> implements LeaderElector {
    private static final Logger LOG = LoggerFactory.getLogger(AbstractLeaderElectorImpl.class);
    private final String id;
    private final LeaderElectionInformation leaderElectionInformation;
    private final T leaderElectionConfiguration;
    private volatile Boolean isLeader;
    private volatile Instant timestamp;
    private volatile String description;
    private volatile boolean isInitialized;
    
    
    /**
     * Constructor for AbstractLeaderElectorImpl
     * 
     * @param leaderElectionInformation the leader election information
     * @param leaderElectionConfiguration the leader election configuration
     */
    public AbstractLeaderElectorImpl(LeaderElectionInformation leaderElectionInformation, T leaderElectionConfiguration) {
        this.id = UUID.randomUUID().toString();
        this.leaderElectionInformation = leaderElectionInformation;
        this.leaderElectionConfiguration = leaderElectionConfiguration;
        isLeader = null;
        description = null;
        isInitialized = false;
    }

    
    /**
     * @see com.github.toolarium.leader.election.LeaderElector#getId()
     */
    @Override
    public String getId() {
        return id;
    }

    
    /**
     * @see com.github.toolarium.leader.election.LeaderElector#initialize()
     * @throws LeaderElectionException In case of an initialisation error
     */
    public void initialize() throws LeaderElectionException {
        synchronized (this) {
            if (!isInitialized) {
                initializeImplementation();
                isInitialized = true;
            }
        }
    }
    
    
    /**
     * Check if it is initialized
     *
     * @return true if it is initialized
     */
    protected boolean isInitialized() {
        return isInitialized;
    }
    
    
    /**
     * @see com.github.toolarium.leader.election.LeaderElector#isLeader()
     */
    @Override
    public boolean isLeader() {
        if (isLeader != null) {
            if (isLeader.booleanValue()) {
                //LOG.debug("In lead of [" + getUniqueName() + "]" + description);
                return true;
            }
            
            //LOG.debug("New leader found for [" + getUniqueName() + "]");
            return false;
        }

        return false;
    }


    /**
     * @see com.github.toolarium.leader.election.LeaderElector#getLeaderElectorTimestamp()
     */
    @Override
    public Instant getLeaderElectorTimestamp() {
        return timestamp;
    }

    
    /**
     * @see com.github.toolarium.leader.election.LeaderElector#close()
     */
    @Override
    public void close() {
        if (isLeader != null && isLeader) {
            LOG.debug("Release lead of [" + leaderElectionInformation.getUniqueName() + "]" + description);
        }

        LeaderElectionScheduler.getInstance().unregister(this);
        isLeader = null;
        description = null;
        isInitialized = false;
    }

    
    /**
     * Initialise the leader elector.
     * This has to be called before the first isLeader() call; otherwise it will be always false. 
     * 
     * @throws LeaderElectionException In case of an initialisation error
     */
    protected abstract void initializeImplementation() throws LeaderElectionException;
        
    
    /**
     * Set the leader
     *
     * @param isLeader the leader
     * @param inputDescription the description
     */
    protected void setLeader(final Boolean isLeader, final String inputDescription) {
        String newDescription;
        if (inputDescription != null) {
            newDescription = " (" + inputDescription + ")."; 
        } else {
            newDescription = ".";
        }
        
        if (isLeader == null) {
            // unset leader
            if (this.isLeader != null && this.isLeader) {
                LOG.debug("Losed lead of [" + leaderElectionInformation.getUniqueName() + "]" + newDescription);
            }
            
            this.timestamp = null;
        } else if (isLeader.booleanValue()) {
            // I'm the leader
            if (this.isLeader == null || !this.isLeader) {
                LOG.debug("Get in lead of [" + leaderElectionInformation.getUniqueName() + "]" + newDescription);
                this.timestamp = Instant.now();
            }
        } else {
            this.timestamp = null;
        }
        
        this.isLeader = isLeader;
        this.description = newDescription;
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
    protected T getLeaderElectionConfiguration() {
        return leaderElectionConfiguration;
    }
}
