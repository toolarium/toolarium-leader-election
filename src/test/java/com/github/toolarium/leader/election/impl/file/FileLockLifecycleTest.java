/*
 * FileLockLifecycleTest.java
 *
 * Copyright by toolarium, all rights reserved.
 */
package com.github.toolarium.leader.election.impl.file;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.github.toolarium.leader.election.dto.LeaderElectionInformation;
import com.github.toolarium.leader.election.dto.file.FileLeaderElectionConfiguration;
import com.github.toolarium.leader.election.exception.LeaderElectionException;
import com.github.toolarium.leader.election.impl.file.exception.FileLockException;
import java.io.File;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;


/**
 * Tests the life cycle of FILE locks: closed locks are removed and never acquired again, close() and the first elect() do
 * not leak a lock, and the lock is kept while a leader task still runs.
 *
 * @author patrick
 */
public class FileLockLifecycleTest {

    /**
     * A file lock is equal to itself only, independent of the fields that change while it is used.
     *
     * @throws Exception in case of an error
     */
    @Test
    public void testLockIdentity() throws Exception {
        File file = Files.createTempFile("file-lock-identity", ".lock").toFile();
        FileLock first = new FileLock(file, Duration.ofSeconds(4), Duration.ofSeconds(3));
        FileLock second = new FileLock(file, Duration.ofSeconds(4), Duration.ofSeconds(3));
        try {
            int hashCode = first.hashCode();
            first.lock();
            assertEquals(hashCode, first.hashCode(), "The hash code must not change when the lock is acquired");
            first.close();
            assertEquals(hashCode, first.hashCode(), "The hash code must not change on close");
            assertNotEquals(first, second);
        } finally {
            first.close();
            second.close();
        }
    }


    /**
     * A closed lock can neither be acquired nor renewed again.
     *
     * @throws Exception in case of an error
     */
    @Test
    public void testClosedLockIsNotAcquiredAgain() throws Exception {
        File file = Files.createTempFile("file-lock-closed", ".lock").toFile();
        FileLock lock = new FileLock(file, Duration.ofSeconds(4), Duration.ofSeconds(3));
        lock.lock();
        assertTrue(lock.hasLock());
        lock.close();
        assertFalse(lock.hasLock());
        assertThrows(FileLockException.class, lock::lock);
        assertThrows(FileLockException.class, lock::renew);
        assertFalse(lock.hasLock());
    }


    /**
     * Elect and close cycles do not leave locks behind in the factory.
     *
     * @throws Exception in case of an error
     */
    @Test
    public void testClosedLocksAreRemoved() throws Exception {
        Path basePath = Files.createTempDirectory("file-lock-removed");
        int before = lockCount();
        for (int i = 0; i < 5; i++) {
            FileLeaderElectorImpl elector = createElector(basePath, "file-lock-removed");
            assertTrue(elector.elect(Duration.ofSeconds(5)));
            elector.close();
        }
        assertEquals(before, lockCount(), "Closed locks must be removed from the factory");
    }


    /**
     * A close() racing the first elect() waits for the initialization and releases the lock it created.
     *
     * @throws Exception in case of an error
     */
    @Test
    public void testCloseDuringFirstElectReleasesTheLock() throws Exception {
        Path basePath = Files.createTempDirectory("file-lock-close-race");
        CountDownLatch initializing = new CountDownLatch(1);
        FileLeaderElectorImpl slow = new FileLeaderElectorImpl(new LeaderElectionInformation("file-lock-close-race"), createConfiguration(basePath)) {
            /**
             * @see com.github.toolarium.leader.election.impl.file.FileLeaderElectorImpl#initializeImplementation()
             */
            @Override
            protected void initializeImplementation() throws LeaderElectionException {
                initializing.countDown();
                try {
                    Thread.sleep(500);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                super.initializeImplementation();
            }
        };
        Thread electThread = new Thread(() -> {
            try {
                slow.elect(Duration.ofSeconds(5));
            } catch (Exception e) {
                // NOP
            }
        });
        electThread.start();
        assertTrue(initializing.await(5, TimeUnit.SECONDS));
        slow.close();
        electThread.join(5000);

        FileLeaderElectorImpl other = createElector(basePath, "file-lock-close-race");
        try {
            assertTrue(other.elect(Duration.ofSeconds(5)), "close() must release the lock created by the concurrent initialization");
        } finally {
            other.close();
        }
    }


    /**
     * The lock is kept while a leader task still runs after the close timeout, and released once it ended.
     *
     * @throws Exception in case of an error
     */
    @Test
    public void testLockIsKeptWhileTaskRuns() throws Exception {
        Path basePath = Files.createTempDirectory("file-lock-task");
        FileLeaderElectorImpl leader = createElector(basePath, "file-lock-task");
        FileLeaderElectorImpl other = createElector(basePath, "file-lock-task");
        CountDownLatch taskStarted = new CountDownLatch(1);
        CountDownLatch releaseTask = new CountDownLatch(1);
        AtomicReference<Throwable> taskError = new AtomicReference<>();
        try {
            assertTrue(leader.elect(Duration.ofSeconds(5)));
            Thread taskThread = new Thread(() -> {
                try {
                    leader.runIfLeader("file-lock-task", lease -> {
                        taskStarted.countDown();
                        // ignores interrupts like a blocking I/O call
                        boolean done = false;
                        while (!done) {
                            try {
                                done = releaseTask.await(10, TimeUnit.SECONDS);
                            } catch (InterruptedException e) {
                                // keep running
                            }
                        }
                    });
                } catch (Throwable t) {
                    taskError.set(t);
                }
            });
            taskThread.start();
            assertTrue(taskStarted.await(5, TimeUnit.SECONDS));

            leader.close();
            assertFalse(leader.isLeader());
            assertFalse(other.elect(Duration.ofSeconds(3)), "The lock must stay held while the task runs");

            releaseTask.countDown();
            taskThread.join(5000);
            assertTrue(waitFor(other::isLeader, 5000), "The lock must be released once the task ended");
        } finally {
            releaseTask.countDown();
            leader.close();
            other.close();
        }
    }


    /**
     * Create a FILE elector.
     *
     * @param basePath the base path
     * @param name the election name
     * @return the elector
     */
    private static FileLeaderElectorImpl createElector(Path basePath, String name) {
        return new FileLeaderElectorImpl(new LeaderElectionInformation(name), createConfiguration(basePath));
    }


    /**
     * Create a FILE configuration with a short close timeout.
     *
     * @param basePath the base path
     * @return the configuration
     */
    private static FileLeaderElectionConfiguration createConfiguration(Path basePath) {
        FileLeaderElectionConfiguration config = new FileLeaderElectionConfiguration(basePath.toString(), Duration.ofSeconds(4), Duration.ofSeconds(3), Duration.ofSeconds(1));
        config.setCloseTimeout(Duration.ofSeconds(1));
        return config;
    }


    /**
     * Gets the number of locks known to the factory.
     *
     * @return the number of locks
     * @throws Exception in case of an error
     */
    private static int lockCount() throws Exception {
        Field field = FileLockFactory.class.getDeclaredField("fileLockMap");
        field.setAccessible(true);
        return ((Map<?, ?>) field.get(FileLockFactory.getInstance())).size();
    }


    /**
     * Wait until a condition is true.
     *
     * @param condition the condition
     * @param timeoutMillis the maximum time to wait
     * @return true if the condition is true
     * @throws InterruptedException if interrupted
     */
    private static boolean waitFor(java.util.function.BooleanSupplier condition, long timeoutMillis) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis);
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() > deadline) {
                return false;
            }
            Thread.sleep(20);
        }
        return true;
    }
}
