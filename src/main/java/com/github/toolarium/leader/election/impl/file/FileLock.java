/*
 * FileLock.java
 *
 * Copyright by toolarium, all rights reserved.
 */
package com.github.toolarium.leader.election.impl.file;

import com.github.toolarium.leader.election.impl.file.exception.FileLockException;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.channels.OverlappingFileLockException;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


/**
 * Implements a file lock
 * 
 * @author patrick
 */
public class FileLock implements AutoCloseable {
    private static final Logger LOG = LoggerFactory.getLogger(FileLock.class);
    private UUID id;
    private File file;
    private Long threadId;
    private String user;
    private Duration lockPeriod;
    private Duration renewDeadline;
    private IFileLockListener fileLockListener;

    private volatile Boolean isValid; 
    private transient FileOutputStream outputStream;
    private transient java.nio.channels.FileLock fileLock;
    private transient Instant lockTime;

    
    /**
     * Constructor for FileLock
     *
     * @param file the file
     * @throws IOException In case of an I/O error
     */
    public FileLock(File file) {
        this(file, null, null);
    }
    
    
    /**
     * Constructor for FileLock
     *
     * @param file the file
     * @param lockPeriod the lock period
     * @param renewDeadline the ewnew deadline 
     * @throws IOException In case of an I/O error
     */
    public FileLock(File file, Duration lockPeriod, Duration renewDeadline) {
        this.id = UUID.randomUUID();
        this.file = file;
        this.threadId = Thread.currentThread().threadId();
        this.user = System.getProperty("user.name");
        this.lockPeriod = lockPeriod;
        this.renewDeadline = renewDeadline;
        
        this.outputStream = null;
        this.fileLock = null;
        this.lockTime = null;
        this.isValid = null; 
    }

    
    /**
     * Set the file lock listener
     *
     * @param fileLockListener the file lock listener
     */
    public void setFileLockListener(IFileLockListener fileLockListener) {
        this.fileLockListener = fileLockListener;

        if (fileLockListener != null && isValid != null) {
            if (isValid) {
                fileLockListener.hasLock();
            } else {
                fileLockListener.releaseLock();
            }
        }
    }
    
    
    /**
     * Renew the file lock
     *
     * @throws FileLockException In case the lock can not be acquired.
     */
    public void renew() throws FileLockException {
        LOG.debug("Renew file lock [" + getId() + "] [" + getFile() + "] on " + getLockTime() + ".");
        unlock();
        lock();
    }
    
    
    /**
     * @see java.lang.AutoCloseable#close()
     */
    @Override
    public void close() throws Exception {
        unlock();
        
        if (file != null) {
            if (!this.file.delete()) {
                this.file.deleteOnExit();
            }
        }

        this.threadId = null;
        this.lockTime = null;
        this.user = null;
        this.lockPeriod = null;
        this.renewDeadline = null;
    }

    
    /**
     * Get the id
     *
     * @return the id
     */
    public UUID getId() {
        return id;
    }

    
    /**
     * Get the lock file
     *
     * @return the lock file
     */
    public File getFile() {
        return file;
    }
    

    /**
     * Get the thread id which holds the lock
     *
     * @return the thread id
     */
    public Long getThreadId() {
        return threadId;
    }
    
    
    /**
     * True if the FileLock has the lock
     *
     * @return true if it has the lock
     */
    public boolean hasLock() {
        return (isValid != null) && isValid.booleanValue();
    }
    
    
    /**
     * Get the user
     *
     * @return the user
     */
    public String getUser() {
        return user;
    }

    
    /**
     * Get the lock time
     *
     * @return the lock time
     */
    public Instant getLockTime() {
        return lockTime;
    }

    
    /**
     * Get the timeout
     *
     * @return the timeout
     */
    public Duration getLockPeriod() {
        return lockPeriod;
    }
    
    
    /**
     * Get the renew deadline
     *
     * @return the renew deadline
     */
    public Duration getRenewDeadline() {
        return renewDeadline;
    }
    

    /**
     * @see java.lang.Object#hashCode()
     */
    @Override
    public int hashCode() {
        return Objects.hash(file, threadId, user, lockTime, lockPeriod, renewDeadline);
    }

    
    /**
     * @see java.lang.Object#equals(java.lang.Object)
     */
    @Override
    public boolean equals(Object obj) {
        if (this == obj) {
            return true;
        }
        
        if (obj == null) {
            return false;
        }
        
        if (getClass() != obj.getClass()) {
            return false;
        }
        
        FileLock other = (FileLock) obj;
        return Objects.equals(file, other.file) && Objects.equals(id, other.id)  && Objects.equals(threadId, other.threadId) && Objects.equals(user, other.user)  
                && Objects.equals(lockTime, other.lockTime) && Objects.equals(lockPeriod, other.lockPeriod) && Objects.equals(renewDeadline, other.renewDeadline);
    }
    

    /**
     * @see java.lang.Object#toString()
     */
    @Override
    public String toString() {
        return "FileLock [id=" + id + ", file=" + file + ", user=" + user + ", lockTime=" + lockTime + ", lockPeriod=" + lockPeriod + ", renewDeadline=" + renewDeadline + "]";
    }

    
    /**
     * Lock
     *
     * @throws FileLockException In case the lock can not be acquired.
     */
    public void lock() throws FileLockException {
        try {
            this.outputStream = new FileOutputStream(file);
            this.isValid = false; 
        } catch (FileNotFoundException ef) {
            throw new FileLockException("Could not aquire the lock [" + getId() + " ] [" + getFile() + "].");
        }

        try {
            this.fileLock = outputStream.getChannel().tryLock();
            this.lockTime = Instant.now();
        } catch (OverlappingFileLockException | IOException ex) {
            try {
                outputStream.close();
            } catch (IOException e) {
                // NOP
            }
            
            this.lockTime = null;
            this.outputStream = null;
            this.isValid = false; 
            throw new FileLockException("Could not aquire the lock [" + getId() + " ] [" + getFile() + "]: " + ex.getMessage());
        }
        
        if (fileLock == null) {
            try {
                outputStream.close();
            } catch (IOException e) {
                // NOP
            }
            
            this.lockTime = null;
            this.outputStream = null;
            this.isValid = false; 
            throw new FileLockException("Could not aquire the lock of the file " + file + ", invalid state.");
        }

        this.isValid = true; 
        LOG.debug("Created file lock [" + getId() + "] [" + getFile() + "] on " + getLockTime() + ".");

        if (fileLockListener != null) {
            fileLockListener.hasLock();
        }
    }
    
    
    /**
     * Unlock
     */
    public void unlock() {
        if (hasLock()) {
            LOG.debug("Release file lock [" + getId() + "] [" + getFile() + "] on " + getLockTime() + ".");
        }
        
        if (outputStream != null) {
            try {
                outputStream.close();
            } catch (IOException e) {
                // NOP
            }
            this.outputStream = null;
        }

        if (fileLock != null) {
            try {
                fileLock.close();
            } catch (IOException e) {
                // NOP
            }
            this.fileLock = null;
        }

        this.lockTime = null;
        this.isValid = false;
        
        if (fileLockListener != null) {
            fileLockListener.releaseLock();
        }
    }
}
