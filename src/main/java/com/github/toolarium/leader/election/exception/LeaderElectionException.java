/*
 * LeaderElectionException.java
 *
 * Copyright by toolarium, all rights reserved.
 */
package com.github.toolarium.leader.election.exception;

/**
 * Defines the leader election exception.
 * 
 * @author patrick
 */
public class LeaderElectionException extends Exception {
    private static final long serialVersionUID = -3180230471577003528L;

    
    /**
     * Constructor for LeaderElectionException
     *
     * @param message the message
     */
    public LeaderElectionException(String message) {
        super(message);
    }
    

    /**
     * Constructor for LeaderElectionException
     *
     * @param message the message
     * @param e the exception
     */
    public LeaderElectionException(String message, Exception e) {
        super(message, e);
    }
}
