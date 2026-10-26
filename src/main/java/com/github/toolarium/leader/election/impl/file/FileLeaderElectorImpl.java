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
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;


/**
 * Implements the {@link ILeaderElector} based on file access.
 * 
 * @author patrick
 */
public class FileLeaderElectorImpl extends AbstractLeaderElectorImpl<FileLeaderElectionConfiguration> {
    // volatile: written by elect()/close() and read by runIfLeader task threads in extendLeaseNow()
    // and by the JVM shutdown hook.
    private volatile FileLock fileLock;

    
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
        File file = new File(basePath, createLockFileName(getLeaderElectionInformation().getElectionGroupName()));
        Path baseDirectory = Paths.get(basePath).toAbsolutePath().normalize();
        if (!baseDirectory.equals(file.toPath().toAbsolutePath().normalize().getParent())) {
            throw new LeaderElectionException("Invalid lock file [" + file + "]: it must be located directly in [" + baseDirectory + "]!");
        }
        fileLock = FileLockFactory.getInstance().tryLock(file, getLeaderElectionConfiguration().getTimeout(), getLeaderElectionConfiguration().getRenewDeadline(),
                                                         getLeaderElectionConfiguration().getRetryPeriod());
        
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
     * Create the lock file name of an election group. Letters, digits, '.', '-' and '_' are kept;
     * every other character, including path separators, is replaced by '_', so the namespace and
     * name cannot point outside the base path. When a character was replaced, a hash of the original
     * name is appended, so different names (e.g. {@code a/b} and {@code a_b}) never share a lock file.
     *
     * @param electionGroupName the election group name
     * @return the lock file name
     */
    static String createLockFileName(String electionGroupName) {
        String original = "";
        if (electionGroupName != null) {
            original = electionGroupName;
        }
        String name = original.trim();

        StringBuilder result = new StringBuilder(name.length() + 14);
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            if (Character.isLetterOrDigit(c) || c == '.' || c == '-' || c == '_') {
                result.append(c);
            } else {
                result.append('_');
            }
        }
        if (!result.toString().equals(original)) {
            result.append('-').append(hash(original));
        }
        return result.append(".lock").toString();
    }


    /**
     * The first 8 hexadecimal digits of the SHA-256 hash of a text.
     *
     * @param text the text
     * @return the hash
     * @throws IllegalStateException if SHA-256 is not available
     */
    private static String hash(String text) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(8);
            for (int i = 0; i < 4; i++) {
                hex.append(String.format("%02x", digest[i]));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            // every Java platform provides SHA-256
            throw new IllegalStateException(e);
        }
    }


    /**
     * @see com.github.toolarium.leader.election.impl.AbstractLeaderElectorImpl#extendLeaseNow()
     */
    @Override
    protected boolean extendLeaseNow() throws LeaderElectionException {
        // Read the field once: close() may null it concurrently from another thread.
        FileLock currentLock = fileLock;
        if (!isLeader() || currentLock == null) {
            return false;
        }
        try {
            currentLock.renew();
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
        closeSerialized(getLeaderElectionConfiguration().getCloseTimeout().toMillis(), this::closeNow);
    }


    /**
     * Close the elector. The lock is released once no leader task runs anymore.
     *
     * @throws LeaderElectionException in case of an error
     */
    private void closeNow() throws LeaderElectionException {
        final FileLock lockToRelease = fileLock;
        fileLock = null;
        if (lockToRelease != null) {
            // the lock stays held until the tasks ended, but must not report to this elector anymore (it may be elected again)
            lockToRelease.setFileLockListener(null);
        }

        boolean tasksFinished = false;
        try {
            tasksFinished = closeAndAwaitTasks();
        } finally {
            if (lockToRelease != null) {
                releaseWhenTasksFinished(tasksFinished, "leader-election-file-release-" + getLeaderElectionInformation().getName(),
                                         () -> FileLockFactory.getInstance().release(lockToRelease));
            }
        }
    }
}
