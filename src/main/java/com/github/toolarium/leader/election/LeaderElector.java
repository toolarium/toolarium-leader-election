/*
 * LeaderElector.java
 *
 * Copyright by toolarium, all rights reserved.
 */
package com.github.toolarium.leader.election;

import com.github.toolarium.leader.election.exception.LeaderElectionException;
import java.time.Instant;

/**
 * Leader elector interface.
 *  
 * @author patrick
 */
public interface LeaderElector extends AutoCloseable {
    
    /**
     * The unique leader elector identifier
     *
     * @return unique leader elector identifier
     */
    String getId(); 

    
    /**
     * Initialise the leader elector.
     * This has to be called before the first isLeader() call; otherwise it will be always false. 
     * 
     * @throws LeaderElectionException in case of an initialization error
     */
    void initialize() throws LeaderElectionException;

    
    /**
     * Close and release all resources held by this leader elector.
     * Implementations may throw {@link LeaderElectionException} if cleanup cannot complete
     * within the configured {@code closeTimeout}.
     *
     * @throws LeaderElectionException if the elector cannot be shut down cleanly in time
     */
    @Override
    void close() throws LeaderElectionException;


    /**
     * Is leader
     *
     * @return true if the caller is the elected leader otherwise false
     */
    boolean isLeader();
    
    
    /**
     * Get the leader elector timestamp when it was selected as leader
     *
     * @return the timestamp or null
     */
    Instant getLeaderElectorTimestamp();   
}
