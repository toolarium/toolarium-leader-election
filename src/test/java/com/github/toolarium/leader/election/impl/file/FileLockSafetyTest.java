/*
 * FileLockSafetyTest.java
 *
 * Copyright by toolarium, all rights reserved.
 */
package com.github.toolarium.leader.election.impl.file;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.github.toolarium.leader.election.ILeaderElector;
import com.github.toolarium.leader.election.LeaderElectionFactory;
import com.github.toolarium.leader.election.dto.LeaderElectionConfiguration;
import com.github.toolarium.leader.election.dto.LeaderElectionInformation;
import com.github.toolarium.leader.election.dto.LeaderElectionStrategy;
import com.github.toolarium.leader.election.impl.file.exception.FileLockException;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;


/**
 * Regression tests for the FILE strategy: in-place renew, lock file handling and the scheduler thread.
 *
 * @author patrick
 */
public class FileLockSafetyTest {
    private static final String BUILD_DIR = "build";
    private static final String LOCK_SUFFIX = ".lock";


    /**
     * renew() of a held lock must keep the OS lock and must not fire releaseLock().
     *
     * @throws Exception in case of an error
     */
    @Test
    public void testRenewKeepsHeldLockWithoutReleaseCallback() throws Exception {
        File file = Paths.get(BUILD_DIR, "testRenewInPlace-" + System.nanoTime() + LOCK_SUFFIX).toFile();
        FileLock fileLock = new FileLock(file, null, Duration.ofSeconds(1));
        AtomicInteger hasLockCount = new AtomicInteger();
        AtomicInteger releaseCount = new AtomicInteger();
        try {
            fileLock.lock();
            fileLock.setFileLockListener(new IFileLockListener() {
                /**
                 * @see com.github.toolarium.leader.election.impl.file.IFileLockListener#hasLock()
                 */
                @Override
                public void hasLock() {
                    hasLockCount.incrementAndGet();
                }

                /**
                 * @see com.github.toolarium.leader.election.impl.file.IFileLockListener#releaseLock()
                 */
                @Override
                public void releaseLock() {
                    releaseCount.incrementAndGet();
                }
            });
            final Instant before = fileLock.getLockTime();
            Thread.sleep(20);

            fileLock.renew();

            assertTrue(fileLock.hasLock());
            assertTrue(fileLock.getLockTime().isAfter(before));
            assertEquals(0, releaseCount.get(), "renew() of a held lock must not release it");
            assertTrue(hasLockCount.get() >= 2);
        } finally {
            fileLock.close();
            Files.deleteIfExists(file.toPath());
        }
    }


    /**
     * close() must not delete the lock file (POSIX inode race) and must not truncate existing content.
     *
     * @throws Exception in case of an error
     */
    @Test
    public void testLockFileIsNeitherTruncatedNorDeleted() throws Exception {
        Path path = Paths.get(BUILD_DIR, "testKeepLockFile-" + System.nanoTime() + LOCK_SUFFIX);
        Files.write(path, "keep".getBytes(StandardCharsets.UTF_8));

        FileLock fileLock = new FileLock(path.toFile());
        fileLock.lock();
        assertTrue(fileLock.hasLock());
        fileLock.close();

        assertTrue(Files.exists(path), "lock file must not be deleted on close");
        assertEquals("keep", new String(Files.readAllBytes(path), StandardCharsets.UTF_8));
        Files.deleteIfExists(path);
    }


    /**
     * A symbolic link must be refused and its target must stay untouched.
     *
     * @throws Exception in case of an error
     */
    @Test
    public void testSymbolicLinkIsRefused() throws Exception {
        Path target = Paths.get(BUILD_DIR, "testSymlinkTarget-" + System.nanoTime() + ".txt").toAbsolutePath();
        Path link = Paths.get(BUILD_DIR, "testSymlink-" + System.nanoTime() + LOCK_SUFFIX).toAbsolutePath();
        Files.write(target, "victim".getBytes(StandardCharsets.UTF_8));
        try {
            try {
                Files.createSymbolicLink(link, target);
            } catch (Exception e) {
                Assumptions.assumeTrue(false, "Symbolic links not supported here: " + e.getMessage());
            }

            FileLock fileLock = new FileLock(link.toFile());
            assertThrows(FileLockException.class, fileLock::lock);
            assertFalse(fileLock.hasLock());
            assertEquals("victim", new String(Files.readAllBytes(target), StandardCharsets.UTF_8));
        } finally {
            Files.deleteIfExists(link);
            Files.deleteIfExists(target);
        }
    }


    /**
     * The lead ends when the lock is not renewed within the renew deadline (e.g. a lock handler hanging on a stalled network
     * file system), and counts again once the renewals resume.
     *
     * @throws Exception in case of an error
     */
    @Test
    public void testLeadIsFencedWithoutRenewals() throws Exception {
        LeaderElectionConfiguration config = new LeaderElectionConfiguration(Duration.ofSeconds(3), Duration.ofSeconds(2), Duration.ofSeconds(1));
        ILeaderElector elector = LeaderElectionFactory.getInstance().getLeaderElection(LeaderElectionStrategy.FILE,
                new LeaderElectionInformation("file-fenced-" + System.nanoTime()), config);
        try {
            assertTrue(elector.elect(Duration.ofSeconds(5)));
            // no renewals for a minute
            FileLockFactory.getInstance().setScheduleInterval(60);
            Thread.sleep(2500);
            assertFalse(elector.isLeader(), "Without renewals the lead must end after the renew deadline");

            FileLockFactory.getInstance().setScheduleInterval(1);
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (!elector.isLeader() && System.nanoTime() < deadline) {
                Thread.sleep(50);
            }
            assertTrue(elector.isLeader(), "The lead must count again once the lock is renewed");
        } finally {
            FileLockFactory.getInstance().setScheduleInterval(1);
            elector.close();
        }
    }


    /**
     * close() does not hang behind a lock operation that hangs (e.g. on a stalled network file system): it returns, and the
     * lock is released once the operation has ended.
     *
     * @throws Exception in case of an error
     */
    @Test
    public void testCloseDoesNotHangBehindHangingOperation() throws Exception {
        Path path = Paths.get(BUILD_DIR, "testHangingClose-" + System.nanoTime() + LOCK_SUFFIX).toAbsolutePath();
        FileLock fileLock = new FileLock(path.toFile(), Duration.ofSeconds(10), Duration.ofSeconds(5));
        FileLock other = new FileLock(path.toFile(), Duration.ofSeconds(10), Duration.ofSeconds(5));
        java.util.concurrent.CountDownLatch inOperation = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.CountDownLatch endOperation = new java.util.concurrent.CountDownLatch(1);
        AtomicBoolean hang = new AtomicBoolean(false);
        Thread renewThread = null;
        try {
            fileLock.lock();
            fileLock.setFileLockListener(new IFileLockListener() {
                /**
                 * @see com.github.toolarium.leader.election.impl.file.IFileLockListener#hasLock()
                 */
                @Override
                public void hasLock() {
                    if (!hang.get()) {
                        return;
                    }
                    // the renewal hangs while it holds the state of the lock
                    inOperation.countDown();
                    try {
                        endOperation.await(30, TimeUnit.SECONDS);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                }

                /**
                 * @see com.github.toolarium.leader.election.impl.file.IFileLockListener#releaseLock()
                 */
                @Override
                public void releaseLock() {
                    // NOP
                }
            });
            hang.set(true);
            renewThread = new Thread(() -> {
                try {
                    fileLock.renew();
                } catch (FileLockException e) {
                    // NOP
                }
            }, "test-hanging-renew");
            renewThread.start();
            assertTrue(inOperation.await(10, TimeUnit.SECONDS));

            long start = System.nanoTime();
            fileLock.close();
            long closeMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
            assertTrue(closeMillis < 8000, "close() must not hang behind the operation, took " + closeMillis + " ms");
            assertThrows(FileLockException.class, other::lock, "The lock is still held while the operation hangs");

            endOperation.countDown();
            renewThread.join(5000);
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            boolean locked = false;
            while (!locked && System.nanoTime() < deadline) {
                try {
                    other.lock();
                    locked = true;
                } catch (FileLockException e) {
                    Thread.sleep(50);
                }
            }
            assertTrue(locked, "The lock must be released once the operation has ended");
        } finally {
            endOperation.countDown();
            other.close();
            fileLock.close();
            Files.deleteIfExists(path);
        }
    }


    /**
     * A lock whose renewal hangs (e.g. on a stalled network file system) does not stop the renewals of the other locks.
     *
     * @throws Exception in case of an error
     */
    @Test
    public void testHangingRenewalDoesNotBlockOtherLocks() throws Exception {
        FileLockFactory factory = FileLockFactory.getInstance();
        FileLock hanging = factory.tryLock(Paths.get(BUILD_DIR, "testHangingRenew-" + System.nanoTime() + LOCK_SUFFIX).toFile(), Duration.ofSeconds(3), Duration.ofSeconds(2));
        FileLock healthy = factory.tryLock(Paths.get(BUILD_DIR, "testHealthyRenew-" + System.nanoTime() + LOCK_SUFFIX).toFile(), Duration.ofSeconds(3), Duration.ofSeconds(2));
        java.util.concurrent.CountDownLatch endHang = new java.util.concurrent.CountDownLatch(1);
        try {
            assertTrue(hanging.hasLock() && healthy.hasLock());
            hanging.setFileLockListener(new IFileLockListener() {
                /**
                 * @see com.github.toolarium.leader.election.impl.file.IFileLockListener#hasLock()
                 */
                @Override
                public void hasLock() {
                    // every renewal of this lock hangs
                    try {
                        endHang.await(30, TimeUnit.SECONDS);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                }

                /**
                 * @see com.github.toolarium.leader.election.impl.file.IFileLockListener#releaseLock()
                 */
                @Override
                public void releaseLock() {
                    // NOP
                }
            });

            // during 4 s the healthy lock is renewed again and again (about every second) while the other one hangs
            long maxAgeNanos = 0;
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(4);
            while (System.nanoTime() < deadline) {
                maxAgeNanos = Math.max(maxAgeNanos, healthy.getLockAgeNanos());
                Thread.sleep(50);
            }
            assertTrue(maxAgeNanos < TimeUnit.SECONDS.toNanos(2), "The healthy lock must be renewed within its renew deadline, age was "
                       + TimeUnit.NANOSECONDS.toMillis(maxAgeNanos) + " ms");
        } finally {
            endHang.countDown();
            factory.release(healthy);
            factory.release(hanging);
        }
    }


    /**
     * The lock handler of the FileLockFactory runs only while file locks exist.
     */
    @Test
    public void testFileLockHandlerRunsOnlyWithLocks() {
        FileLockFactory factory = FileLockFactory.getInstance();
        FileLock fileLock = factory.tryLock(Paths.get(BUILD_DIR, "testHandler-" + System.nanoTime() + LOCK_SUFFIX).toFile(), Duration.ofSeconds(10), Duration.ofSeconds(5));
        try {
            assertTrue(factory.isScheduled(), "The lock handler must run while a file lock exists");
        } finally {
            factory.release(fileLock);
        }
        if (factory.getLockCount() == 0) {
            assertFalse(factory.isScheduled(), "The lock handler must stop once no file lock exists anymore");
        }
    }


    /**
     * A lock file that is deleted while it is locked is detected on the next renewal: the lock is acquired again on the new
     * file, so no other process can lock a new file under the same name while this one still believes to hold the lock.
     *
     * @throws Exception in case of an error
     */
    @Test
    public void testDeletedLockFileIsDetected() throws Exception {
        Path path = Paths.get(BUILD_DIR, "testDeleted-" + System.nanoTime() + LOCK_SUFFIX).toAbsolutePath();
        AtomicInteger released = new AtomicInteger();
        FileLock fileLock = new FileLock(path.toFile(), Duration.ofSeconds(10), Duration.ofSeconds(5));
        FileLock other = new FileLock(path.toFile(), Duration.ofSeconds(10), Duration.ofSeconds(5));
        try {
            fileLock.lock();
            fileLock.setFileLockListener(new IFileLockListener() {
                /**
                 * @see com.github.toolarium.leader.election.impl.file.IFileLockListener#hasLock()
                 */
                @Override
                public void hasLock() {
                    // NOP
                }

                /**
                 * @see com.github.toolarium.leader.election.impl.file.IFileLockListener#releaseLock()
                 */
                @Override
                public void releaseLock() {
                    released.incrementAndGet();
                }
            });
            try {
                Files.delete(path);
            } catch (Exception e) {
                Assumptions.assumeTrue(false, "A locked file cannot be deleted here: " + e.getMessage());
            }

            fileLock.renew();
            assertTrue(released.get() > 0, "The lock on the deleted file must be given up");
            assertTrue(fileLock.hasLock(), "The lock must be acquired again on the new file");
            assertTrue(Files.exists(path), "The lock file must exist again");
            assertThrows(FileLockException.class, other::lock, "The new lock file must be locked by the first lock");
        } finally {
            other.close();
            fileLock.close();
            Files.deleteIfExists(path);
        }
    }


    /**
     * A task running longer than renewDeadline must not be interrupted by the background renew,
     * because the node never loses the file lock.
     *
     * @throws Exception in case of an error
     */
    @Test
    public void testLongRunningTaskSurvivesBackgroundRenew() throws Exception {
        ILeaderElector elector = LeaderElectionFactory.getInstance().getLeaderElection(LeaderElectionStrategy.FILE,
                new LeaderElectionInformation("file-renew-" + System.nanoTime()),
                new LeaderElectionConfiguration(Duration.ofSeconds(4), Duration.ofSeconds(2), Duration.ofSeconds(1)));
        try {
            assertTrue(elector.elect(Duration.ofSeconds(5)));
            AtomicBoolean interrupted = new AtomicBoolean(false);
            AtomicBoolean invalid = new AtomicBoolean(false);
            assertTrue(elector.runIfLeader("long", lease -> {
                try {
                    // 5 s > renewDeadline (2 s): at least two background renew cycles happen meanwhile.
                    for (int i = 0; i < 50; i++) {
                        Thread.sleep(100);
                        if (!lease.isValid()) {
                            invalid.set(true);
                        }
                    }
                } catch (InterruptedException e) {
                    interrupted.set(true);
                }
            }));
            assertFalse(interrupted.get(), "task must not be interrupted by the background renew");
            assertFalse(invalid.get(), "lease must stay valid across background renews");
            assertTrue(elector.isLeader());
        } finally {
            elector.close();
        }
    }
}
