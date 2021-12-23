/*
 * ILeaderElector.java
 *
 * Copyright by toolarium, all rights reserved.
 */
package com.github.toolarium.leader.election;

/**
 * Leader elector interface.
 *  
 * @author patrick
 */
public interface ILeaderElector {
    
    /**
     * Is leader
     * 
     * @return true if the caller is the elected leader otherwise false
     */
    boolean isLeader();

}
