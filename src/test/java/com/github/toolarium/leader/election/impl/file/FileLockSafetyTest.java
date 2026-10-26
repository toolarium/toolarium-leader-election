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

    /**
     * renew() of a held lock must keep the OS lock and must not fire releaseLock().
     *
     * @throws Exception in case of an error
     */
    @Test
    public void testRenewKeepsHeldLockWithoutReleaseCallback() throws Exception {
        File file = Paths.get("build", "testRenewInPlace-" + System.nanoTime() + ".lock").toFile();
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
        Path path = Paths.get("build", "testKeepLockFile-" + System.nanoTime() + ".lock");
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
        Path target = Paths.get("build", "testSymlinkTarget-" + System.nanoTime() + ".txt").toAbsolutePath();
        Path link = Paths.get("build", "testSymlink-" + System.nanoTime() + ".lock").toAbsolutePath();
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
     * The FileLockFactory scheduler thread must be a daemon so it does not keep the JVM alive.
     */
    @Test
    public void testFileLockFactorySchedulerIsDaemon() {
        FileLockFactory.getInstance();
        boolean found = false;
        for (Thread thread : Thread.getAllStackTraces().keySet()) {
            if ("leader-election-file-lock".equals(thread.getName())) {
                found = true;
                assertTrue(thread.isDaemon(), "FileLockFactory scheduler thread must be a daemon thread");
            }
        }
        assertTrue(found, "FileLockFactory scheduler thread not found");
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
