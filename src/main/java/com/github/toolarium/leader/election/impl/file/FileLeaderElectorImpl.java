/*
 * FileLeaderElectorImpl.java
 *
 * Copyright by toolarium, all rights reserved.
 */
package com.github.toolarium.leader.election.impl.file;

import com.github.toolarium.leader.election.ILeaderElector;
import com.github.toolarium.leader.election.dto.LeaderElectionInformation;
import com.github.toolarium.leader.election.dto.file.FileLeaderElectionConfiguration;
import com.github.toolarium.leader.election.exception.LeaderElectionException;
import com.github.toolarium.leader.election.impl.AbstractLeaderElectorImpl;
import com.github.toolarium.leader.election.impl.file.exception.FileLockException;
import java.io.File;


/**
 * Implements the {@link ILeaderElector} based on file access.
 * 
 * @author patrick
 */
public class FileLeaderElectorImpl extends AbstractLeaderElectorImpl<FileLeaderElectionConfiguration> {
    private FileLock fileLock;

    
    /**
     * Constructor for FileLeaderElectorImpl
     *
     * @param leaderElectionInformation the leader election information
     * @param leaderElectionConfiguration the leader election configuration
     */
    public FileLeaderElectorImpl(LeaderElectionInformation leaderElectionInformation, FileLeaderElectionConfiguration leaderElectionConfiguration) {
        super(leaderElectionInformation, leaderElectionConfiguration);
        fileLock = null;
    }

    
    /**
     * @see com.github.toolarium.leader.election.impl.AbstractLeaderElectorImpl#initializeImplementation()
     */
    @Override
    protected void initializeImplementation() throws LeaderElectionException {
        FileLeaderElectionConfiguration.createBasePath(null);
        String basePath = getLeaderElectionConfiguration().getBasePath();
        File file = new File(basePath + getLeaderElectionInformation().getUniqueName().trim() + ".lock");
        fileLock = FileLockFactory.getInstance().tryLock(file, getLeaderElectionConfiguration().getTimeout(), getLeaderElectionConfiguration().getRenewDeadline());
        
        // in case we got the lock we set the listener
        fileLock.setFileLockListener(new IFileLockListener() {
            /**
             * @see com.github.toolarium.leader.election.impl.file.IFileLockListener#hasLock()
             */
            @Override
            public void hasLock() {
                setLeader(true, null);
            }

            /**
             * @see com.github.toolarium.leader.election.impl.file.IFileLockListener#releaseLock()
             */
            @Override
            public void releaseLock() {
                setLeader(false, null);
            }
        });
    }
    
    
    /**
     * @see com.github.toolarium.leader.election.impl.AbstractLeaderElectorImpl#extendLeaseNow()
     */
    @Override
    protected boolean extendLeaseNow() throws LeaderElectionException {
        if (!isLeader() || fileLock == null) {
            return false;
        }
        try {
            fileLock.renew();
            return isLeader();
        } catch (FileLockException e) {
            setLeader(false, null);
            return false;
        }
    }


    /**
     * @see com.github.toolarium.leader.election.ILeaderElector#close()
     */
    @Override
    public void close() throws LeaderElectionException {
        FileLock lockToRelease = fileLock;
        fileLock = null;
        try {
            super.close();
        } finally {
            FileLockFactory.getInstance().release(lockToRelease);
        }
    }
}
