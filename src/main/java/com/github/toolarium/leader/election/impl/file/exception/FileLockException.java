/*
 * FileLockException.java
 *
 * Copyright by toolarium, all rights reserved.
 */
package com.github.toolarium.leader.election.impl.file.exception;

/**
 * Defines the file lock exception
 * 
 * @author patrick
 */
public class FileLockException extends Exception {
    private static final long serialVersionUID = -3180230471577003528L;

    
    /**
     * Constructor for FileLockException
     *
     * @param message the message
     */
    public FileLockException(String message) {
        super(message);
    }
}
