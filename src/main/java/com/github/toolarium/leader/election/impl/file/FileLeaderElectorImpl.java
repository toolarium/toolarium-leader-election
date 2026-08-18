/*
 * FileLeaderElectorImpl.java
 *
 * Copyright by toolarium, all rights reserved.
 */
package com.github.toolarium.leader.election.impl.file;

import com.github.toolarium.leader.election.LeaderElector;
import com.github.toolarium.leader.election.dto.FileLeaderElectionConfiguration;
import com.github.toolarium.leader.election.dto.LeaderElectionInformation;
import com.github.toolarium.leader.election.exception.LeaderElectionException;
import com.github.toolarium.leader.election.impl.AbstractLeaderElectorImpl;
import java.io.File;


/**
 * Implements the {@link LeaderElector} based on file access.
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
        String basePath = FileLeaderElectionConfiguration.createBasePath(null);
        if (getLeaderElectionConfiguration() != null && getLeaderElectionConfiguration() instanceof FileLeaderElectionConfiguration) {
            basePath = ((FileLeaderElectionConfiguration)getLeaderElectionConfiguration()).getBasePath(); 
        }
        
        File file = new File(basePath + getLeaderElectionInformation().getUniqueName().replace('.', '.').trim() + ".lock");
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
     * @see com.github.toolarium.leader.election.LeaderElector#close()
     */
    @Override
    public void close() {
        super.close();
        FileLockFactory.getInstance().release(fileLock);
        fileLock = null;
    }
}
