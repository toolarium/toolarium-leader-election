/*
 * FileLock.java
 *
 * Copyright by toolarium, all rights reserved.
 */
package com.github.toolarium.leader.election.impl.file;

import com.github.toolarium.leader.election.impl.LeaderElectionExecutors;
import com.github.toolarium.leader.election.impl.file.exception.FileLockException;
import java.io.File;
import java.io.IOException;
import java.lang.ref.WeakReference;
import java.nio.channels.FileChannel;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileTime;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.locks.ReentrantLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


/**
 * Implements a file lock
 * 
 * @author patrick
 */
public class FileLock implements AutoCloseable {
    private static final Logger LOG = LoggerFactory.getLogger(FileLock.class);
    // close() waits this long for a lock operation that is still running before it releases the lock in the background
    private static final long CLOSE_WAIT_MILLIS = 5000L;
    private UUID id;
    private File file;
    private Long threadId;
    // the thread that created the lock; a weak reference does not keep an ended thread in memory
    private final WeakReference<Thread> ownerThread;
    private String user;
    // volatile: close() clears them while the lock handler reads them
    private volatile Duration lockPeriod;
    private volatile Duration renewDeadline;
    // volatile: setFileLockListener() is called from the initialization thread while the
    // FileLockFactory handler may concurrently read this field in lock()/unlock()/renew().
    // Without volatile the handler may never see the write.
    private volatile IFileLockListener fileLockListener;

    private volatile Boolean isValid;
    // volatile so that getLockTime() / hasLock() reads from other threads always see the
    // latest value written by lock()/unlock()/renew() under the state lock.
    private volatile FileChannel channel;
    private volatile java.nio.channels.FileLock fileLock;
    private volatile Instant lockTime;
    // nanoTime of the last lock or renewal: the renewals are scheduled on the monotonic clock, not on the wall clock
    private volatile long lockNanos;
    // set by close(): a closed lock is never acquired again, e.g. by a renewal of the lock handler racing close()
    private volatile boolean closed;
    // a lock that is not held is tried again after this period; null: on every check of the lock handler
    private final Duration retryPeriod;
    private volatile long lastAttemptNanos;
    private volatile boolean attempted;
    // the file key (e.g. device and inode) of the locked file, or null if the file system provides none
    private volatile Object lockedFileKey;
    // Guards the state of the lock. The checks of the lock file on renewal run without it: a stalled (network) file system
    // must not block close() or the listener.
    private final ReentrantLock stateLock = new ReentrantLock();
    // the check of the lock file that runs on the shared executors, so that a stalled one is not started again
    private volatile Future<Boolean> pendingCheck;
    private final Object checkLock = new Object();

    
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
        this.ownerThread = new WeakReference<>(Thread.currentThread());
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
    public void setFileLockListener(IFileLockListener fileLockListener) {
        if (fileLockListener == null) {
            // detaching needs no lock, so it never waits for a lock operation that hangs
            this.fileLockListener = null;
            return;
        }
        stateLock.lock();
        try {
            this.fileLockListener = fileLockListener;
            if (isValid != null) {
                if (isValid) {
                    fileLockListener.hasLock();
                } else {
                    fileLockListener.releaseLock();
                }
            }
        } finally {
            stateLock.unlock();
        }
    }
    
    
    /**
     * Renew the file lock
     *
     * @throws FileLockException In case the lock can not be acquired.
     */
    public void renew() throws FileLockException {
        java.nio.channels.FileLock currentLock;
        stateLock.lock();
        try {
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
            currentLock = fileLock;
            if (currentLock == null || !currentLock.isValid() || !hasLock()) {
                unlock();
                lock();
                return;
            }
        } finally {
            stateLock.unlock();
        }

        // the lock file is checked without the state lock: a stalled (network) file system must not block close()
        Boolean intact = checkLockedFile(currentLock);
        if (intact == null) {
            // not checked in time: not renewed, so the lead ends after the renew deadline if this keeps failing
            return;
        }

        stateLock.lock();
        try {
            if (closed || fileLock != currentLock) {
                // closed or locked again meanwhile
                return;
            }
            if (intact) {
                this.lockTime = Instant.now();
                this.lockNanos = System.nanoTime();
                // read once: the owner may detach the listener concurrently
                IFileLockListener listener = fileLockListener;
                if (listener != null) {
                    listener.hasLock();
                }
                return;
            }

            // The lock file was deleted or replaced, or the locked file cannot be used anymore (e.g. a network file system lost the
            // lock): another process may hold the lock now. Give it up and compete for the file now at the path.
            LOG.info("Lock file [{}] was deleted or replaced, or the lock was lost; the lock is acquired again.", getFile());
            unlock();
            lock();
        } finally {
            stateLock.unlock();
        }
    }


    /**
     * Check that the lock file is still the locked file and can be used, and update its modification time. With a renew
     * deadline the check runs on the shared executors and is awaited for half of it, so a stalled file system blocks
     * neither the caller nor the renewals of other locks for good.
     *
     * @param currentLock the lock to check
     * @return true if the lock file is intact, false if not, or null if the check did not finish in time
     */
    private Boolean checkLockedFile(java.nio.channels.FileLock currentLock) {
        Callable<Boolean> check = () -> {
            boolean intact = isLockedFileAtPath() && isChannelUsable(currentLock);
            if (intact) {
                touchLockFile();
            }
            return intact;
        };
        Duration deadline = renewDeadline;
        if (deadline == null) {
            return runCheck(check);
        }

        Future<Boolean> future;
        // the check and the submit are atomic: the lock handler and an extension must not both start a check
        synchronized (checkLock) {
            Future<Boolean> pending = pendingCheck;
            if (pending != null && !pending.isDone()) {
                if (LOG.isDebugEnabled()) {
                    LOG.debug("The previous check of lock file [{}] is still running.", getFile());
                }
                return null;
            }
            try {
                future = LeaderElectionExecutors.getInstance().submit("leader-election-file-check", check);
            } catch (RejectedExecutionException e) {
                return runCheck(check);
            }
            pendingCheck = future;
        }
        try {
            return future.get(Math.max(1L, deadline.toMillis() / 2), TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            if (LOG.isDebugEnabled()) {
                LOG.debug("The check of lock file [{}] did not finish in time.", getFile());
            }
            return null;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        } catch (ExecutionException e) {
            return Boolean.FALSE;
        }
    }


    /**
     * Run a check of the lock file on the calling thread.
     *
     * @param check the check
     * @return the result, false if the check failed
     */
    private static Boolean runCheck(Callable<Boolean> check) {
        try {
            return check.call();
        } catch (Exception e) {
            return Boolean.FALSE;
        }
    }


    /**
     * Test if the file at the lock path is still the locked file. Uses the file key (inode) where the file system provides one,
     * otherwise only checks that the file still exists.
     *
     * @return true if the locked file is still at the path
     */
    private boolean isLockedFileAtPath() {
        try {
            Object currentKey = Files.readAttributes(file.toPath(), BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS).fileKey();
            return lockedFileKey == null || lockedFileKey.equals(currentKey);
        } catch (IOException | SecurityException e) {
            // the file does not exist anymore or cannot be read
            if (LOG.isDebugEnabled()) {
                LOG.debug("Could not read the attributes of lock file [{}]: {}", getFile(), e.toString());
            }
            return false;
        }
    }


    /**
     * Test if the channel of the lock can still be used. A network file system that lost the lock (e.g. after a partition
     * longer than the server's lease) fails the operations on the file descriptor, while the local lock object stays valid.
     *
     * @param lock the lock
     * @return true if the channel can be used
     */
    private boolean isChannelUsable(java.nio.channels.FileLock lock) {
        try {
            lock.channel().size();
            return true;
        } catch (IOException e) {
            if (LOG.isDebugEnabled()) {
                LOG.debug("The locked file [{}] cannot be used anymore: {}", getFile(), e.toString());
            }
            return false;
        }
    }


    /**
     * Get the time since the lock was acquired or renewed last, measured with the monotonic clock.
     *
     * @return the age in nanoseconds, or {@link Long#MAX_VALUE} if the lock is not held
     */
    public long getLockAgeNanos() {
        if (lockTime == null) {
            return Long.MAX_VALUE;
        }
        return System.nanoTime() - lockNanos;
    }


    /**
     * Update the modification time of the lock file, so that cleaners of temporary directories do not delete a lock file in use.
     */
    private void touchLockFile() {
        try {
            Files.setLastModifiedTime(file.toPath(), FileTime.from(Instant.now()));
        } catch (IOException | SecurityException e) {
            if (LOG.isDebugEnabled()) {
                LOG.debug("Could not update the modification time of lock file [{}]: {}", getFile(), e.toString());
            }
        }
    }


    /**
     * Releases the lock. The lock file itself is intentionally NOT deleted: on POSIX file systems
     * deleting a lock file allows a waiting process to hold a lock on the unlinked inode while a
     * third process creates and locks a new file under the same name, resulting in two leaders.
     *
     * @see java.lang.AutoCloseable#close()
     */
    @Override
    public void close() throws Exception {
        closed = true;
        // the owner closed the lock: no further callbacks
        fileLockListener = null;

        // The release (a file lock RPC on a network file system) runs on its own thread and is awaited for a bounded time:
        // a stalled file system must neither block close() nor the JVM shutdown.
        Thread releaseThread = new Thread(() -> {
            stateLock.lock();
            try {
                release();
            } finally {
                stateLock.unlock();
            }
        }, "leader-election-file-release");
        releaseThread.setDaemon(true);
        releaseThread.start();
        try {
            releaseThread.join(CLOSE_WAIT_MILLIS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        if (releaseThread.isAlive()) {
            LOG.info("Lock file [{}] could not be released yet (e.g. a stalled network file system); it is released as soon as possible.", getFile());
        }
    }


    /**
     * Release the lock and clear the state of a closed lock. The caller holds the state lock.
     */
    private void release() {
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
     * Test if the lock was closed by its owner.
     *
     * @return true if the lock is closed
     */
    public boolean isClosed() {
        return closed;
    }


    /**
     * Test if the thread that created the lock is still alive. A closed lock has no owner anymore.
     *
     * @return true if the owner thread is alive
     */
    public boolean isOwnerThreadAlive() {
        Thread owner = ownerThread.get();
        return !closed && owner != null && owner.isAlive();
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
    public void lock() throws FileLockException {
        stateLock.lock();
        try {
            lockLocked();
        } finally {
            stateLock.unlock();
        }
    }


    /**
     * Acquire the lock; the caller holds the state lock.
     *
     * @throws FileLockException In case the lock can not be acquired.
     */
    private void lockLocked() throws FileLockException {
        if (closed) {
            throw new FileLockException("Could not aquire the lock [" + getId() + "] [" + getFile() + "]: closed.");
        }
        java.nio.channels.FileLock currentLock = fileLock;
        if (currentLock != null && currentLock.isValid() && hasLock()) {
            // already held: a second channel on the same file would fail and reset the state of the held lock
            return;
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
            this.lockNanos = System.nanoTime();
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

        // the identity of the locked file, to detect a lock file that is deleted or replaced later
        try {
            this.lockedFileKey = Files.readAttributes(file.toPath(), BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS).fileKey();
        } catch (IOException | SecurityException e) {
            this.lockedFileKey = null;
        }
        this.isValid = true;
        if (LOG.isDebugEnabled()) {
            LOG.debug("Created file lock [" + getId() + "] [" + getFile() + "] on " + getLockTime() + ".");
        }

        // read once: the owner may detach the listener concurrently
        IFileLockListener listener = fileLockListener;
        if (listener != null) {
            listener.hasLock();
        }
    }
    
    
    /**
     * Unlock
     */
    public void unlock() {
        stateLock.lock();
        try {
            unlockLocked();
        } finally {
            stateLock.unlock();
        }
    }


    /**
     * Release the lock; the caller holds the state lock.
     */
    private void unlockLocked() {
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
        
        // read once: the owner may detach the listener concurrently
        IFileLockListener listener = fileLockListener;
        if (listener != null) {
            listener.releaseLock();
        }
    }
}
