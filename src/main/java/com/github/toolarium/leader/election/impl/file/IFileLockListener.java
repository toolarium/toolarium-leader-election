/*
 * IFileLockListener.java
 *
 * Copyright by toolarium, all rights reserved.
 */
package com.github.toolarium.leader.election.impl.file;

/**
 * File lock listener
 *
 * @author patrick
 */
public interface IFileLockListener {

    /**
     * Will be called in case if getting the lock
     */
    void hasLock();

    
    /**
     * In case of the lock will be released
     */
    void releaseLock();
}
