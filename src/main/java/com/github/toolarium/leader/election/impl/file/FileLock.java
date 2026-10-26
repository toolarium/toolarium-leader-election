/*
 * FileLock.java
 *
 * Copyright by toolarium, all rights reserved.
 */
package com.github.toolarium.leader.election.impl.file;

import com.github.toolarium.leader.election.impl.file.exception.FileLockException;
import java.io.File;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
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
    // volatile: setFileLockListener() is called from the initialization thread while the
    // FileLockFactory scheduler thread may concurrently read this field inside synchronized
    // lock()/unlock()/renew().  Without volatile the scheduler thread may never see the write.
    private volatile IFileLockListener fileLockListener;

    private volatile Boolean isValid;
    // volatile so that getLockTime() / hasLock() reads from other threads always see the
    // latest value written by the synchronized lock()/unlock()/renew() methods.
    private volatile FileChannel channel;
    private volatile java.nio.channels.FileLock fileLock;
    private volatile Instant lockTime;
    // set by close(): a closed lock is never acquired again, e.g. by a renewal of the lock handler racing close()
    private volatile boolean closed;
    // a lock that is not held is tried again after this period; null: on every check of the lock handler
    private final Duration retryPeriod;
    private volatile long lastAttemptNanos;
    private volatile boolean attempted;

    
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
        this(file, lockPeriod, renewDeadline, null);
    }


    /**
     * Constructor for FileLock
     *
     * @param file the file
     * @param lockPeriod the lock period
     * @param renewDeadline the renew deadline
     * @param retryPeriod the period after which a lock that is not held is tried again, or null to try on every check
     */
    public FileLock(File file, Duration lockPeriod, Duration renewDeadline, Duration retryPeriod) {
        this.retryPeriod = retryPeriod;
        this.attempted = false;
        this.id = UUID.randomUUID();
        this.file = file;
        this.threadId = ThreadUtil.getInstance().getThreadId();
        this.user = System.getProperty("user.name");
        this.lockPeriod = lockPeriod;
        this.renewDeadline = renewDeadline;
        
        this.channel = null;
        this.fileLock = null;
        this.lockTime = null;
        this.isValid = null; 
    }

    
    /**
     * Set the file lock listener
     *
     * @param fileLockListener the file lock listener
     */
    public synchronized void setFileLockListener(IFileLockListener fileLockListener) {
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
    public synchronized void renew() throws FileLockException {
        if (closed) {
            throw new FileLockException("Could not renew the lock [" + getId() + "] [" + getFile() + "]: closed.");
        }
        if (LOG.isDebugEnabled()) {
            LOG.debug("Renew file lock [" + getId() + "] [" + getFile() + "] on " + getLockTime() + ".");
        }

        // An OS file lock stays valid until it is released or the process dies, so a lock that is
        // still held is renewed in place. Releasing and re-acquiring it would open a window in which
        // another process can take the lock, and would fire releaseLock()+hasLock() callbacks that
        // make the elector believe leadership was lost and regained.
        java.nio.channels.FileLock currentLock = fileLock;
        if (currentLock != null && currentLock.isValid() && hasLock()) {
            this.lockTime = Instant.now();
            if (fileLockListener != null) {
                fileLockListener.hasLock();
            }
            return;
        }

        unlock();
        lock();
    }


    /**
     * Releases the lock. The lock file itself is intentionally NOT deleted: on POSIX file systems
     * deleting a lock file allows a waiting process to hold a lock on the unlinked inode while a
     * third process creates and locks a new file under the same name, resulting in two leaders.
     *
     * @see java.lang.AutoCloseable#close()
     */
    @Override
    public synchronized void close() throws Exception {
        closed = true;
        // the owner closed the lock: no further callbacks
        fileLockListener = null;
        unlock();

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
     * Test if a lock that is not held should be tried again: the retry period passed since the last attempt.
     *
     * @return true if the lock should be tried again
     */
    public boolean isRetryDue() {
        return retryPeriod == null || !attempted || System.nanoTime() - lastAttemptNanos >= retryPeriod.toNanos();
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
        // the id only: the other fields change while the lock is used (lock time) and on close()
        return Objects.hash(id);
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
        return Objects.equals(id, other.id);
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
    public synchronized void lock() throws FileLockException {
        if (closed) {
            throw new FileLockException("Could not aquire the lock [" + getId() + "] [" + getFile() + "]: closed.");
        }
        lastAttemptNanos = System.nanoTime();
        attempted = true;

        // Capture the channel in a local variable so that a concurrent unlock() call
        // (which nulls this.channel) cannot cause a NullPointerException in the
        // catch/cleanup blocks below.
        final FileChannel ch;
        try {
            Path path = file.toPath();
            // Refuse anything that is not a regular file (e.g. a symbolic link planted in a shared
            // directory such as java.io.tmpdir pointing to a file owned by the victim).
            if (Files.exists(path, LinkOption.NOFOLLOW_LINKS) && !Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
                throw new FileLockException("Could not aquire the lock [" + getId() + " ] [" + getFile() + "]: not a regular file.");
            }

            // CREATE + WRITE without TRUNCATE_EXISTING: the content of an existing file is never
            // touched; NOFOLLOW_LINKS closes the race between the check above and the open.
            ch = FileChannel.open(path, StandardOpenOption.CREATE, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS);
            this.channel = ch;
            this.isValid = false;
        } catch (IOException | UnsupportedOperationException | SecurityException ef) {
            throw new FileLockException("Could not aquire the lock [" + getId() + " ] [" + getFile() + "]: " + ef.getMessage());
        }

        try {
            this.fileLock = ch.tryLock();
            this.lockTime = Instant.now();
        } catch (OverlappingFileLockException | IOException ex) {
            try {
                ch.close();
            } catch (IOException e) {
                // NOP
            }

            this.lockTime = null;
            this.channel = null;
            this.isValid = false;
            // the lock is held by another elector of this JVM: an expected result, no stack trace
            throw new FileLockException("Could not aquire the lock [" + getId() + " ] [" + getFile() + "]: " + ex.getMessage(), false);
        }

        if (fileLock == null) {
            try {
                ch.close();
            } catch (IOException e) {
                // NOP
            }

            this.lockTime = null;
            this.channel = null;
            this.isValid = false;
            // the lock is held by another process: an expected result, no stack trace
            throw new FileLockException("Could not aquire the lock of the file " + file + ", held by another process.", false);
        }

        this.isValid = true;
        if (LOG.isDebugEnabled()) {
            LOG.debug("Created file lock [" + getId() + "] [" + getFile() + "] on " + getLockTime() + ".");
        }

        if (fileLockListener != null) {
            fileLockListener.hasLock();
        }
    }
    
    
    /**
     * Unlock
     */
    public synchronized void unlock() {
        if (hasLock()) {
            if (LOG.isDebugEnabled()) {
                LOG.debug("Release file lock [" + getId() + "] [" + getFile() + "] on " + getLockTime() + ".");
            }
        }
        
        if (fileLock != null) {
            try {
                fileLock.close();
            } catch (IOException e) {
                // NOP
            }
            this.fileLock = null;
        }

        if (channel != null) {
            try {
                channel.close();
            } catch (IOException e) {
                // NOP
            }
            this.channel = null;
        }

        this.lockTime = null;
        this.isValid = false;
        
        if (fileLockListener != null) {
            fileLockListener.releaseLock();
        }
    }
}
