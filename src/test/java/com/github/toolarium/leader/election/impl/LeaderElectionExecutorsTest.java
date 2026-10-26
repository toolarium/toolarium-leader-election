/*
 * LeaderElectionExecutorsTest.java
 *
 * Copyright by toolarium, all rights reserved.
 */
package com.github.toolarium.leader.election.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.github.toolarium.leader.election.ILeaderElector;
import com.github.toolarium.leader.election.LeaderElectionFactory;
import com.github.toolarium.leader.election.dto.LeaderElectionInformation;
import com.github.toolarium.leader.election.dto.LeaderElectionStrategy;
import com.github.toolarium.leader.election.dto.db.DatabaseLeaderElectionConfiguration;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;


/**
 * Tests the threads shared by all leader electors.
 *
 * @author patrick
 */
public class LeaderElectionExecutorsTest {

    /**
     * The executors are a singleton.
     */
    @Test
    public void testSingleton() {
        assertSame(LeaderElectionExecutors.getInstance(), LeaderElectionExecutors.getInstance());
    }


    /**
     * A run that was skipped because the previous run took longer than the period follows right after that run, not at the
     * next period.
     *
     * @throws Exception in case of an error
     */
    @Test
    public void testMissedRunFollowsAnOverrun() throws Exception {
        List<Long> starts = java.util.Collections.synchronizedList(new ArrayList<>());
        AtomicInteger runs = new AtomicInteger();
        LeaderElectionExecutors.PeriodicTask task = LeaderElectionExecutors.getInstance().schedulePeriodic("test-missed-run", () -> {
            starts.add(System.nanoTime());
            if (runs.incrementAndGet() == 1) {
                // the first run takes longer than two periods: the due runs are skipped
                try {
                    Thread.sleep(700);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
        }, 300);
        try {
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (starts.size() < 2 && System.nanoTime() < deadline) {
                Thread.sleep(10);
            }
            assertTrue(starts.size() >= 2, "A second run must follow");
            long gapMillis = TimeUnit.NANOSECONDS.toMillis(starts.get(1) - starts.get(0));
            // right after the 700 ms run, not at the next 300 ms slot (which could be up to 900 ms after the start)
            assertTrue(gapMillis < 820, "The missed run must follow right after the overrun, gap " + gapMillis + " ms");
        } finally {
            task.cancel();
        }
    }


    /**
     * Runs of one periodic task never overlap; a run that is due while the previous one is active is skipped.
     *
     * @throws Exception in case of an error
     */
    @Test
    public void testPeriodicRunsDoNotOverlap() throws Exception {
        AtomicInteger active = new AtomicInteger();
        AtomicInteger maxActive = new AtomicInteger();
        AtomicInteger runs = new AtomicInteger();
        LeaderElectionExecutors.PeriodicTask task = LeaderElectionExecutors.getInstance().schedulePeriodic("executors-overlap-test", () -> {
            maxActive.accumulateAndGet(active.incrementAndGet(), Math::max);
            runs.incrementAndGet();
            try {
                Thread.sleep(120);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                active.decrementAndGet();
            }
        }, 20);
        try {
            Thread.sleep(700);
        } finally {
            task.cancel();
            assertTrue(task.awaitIdle(1000));
        }
        assertEquals(1, maxActive.get(), "Runs of one task must not overlap");
        assertTrue(runs.get() >= 3, "The task must run periodically, runs: " + runs.get());
    }


    /**
     * The first run starts at once; the second one at a random point between half a period and a full period later, so the
     * gap never exceeds the period.
     *
     * @throws Exception in case of an error
     */
    @Test
    public void testSecondRunWithinOnePeriod() throws Exception {
        long periodMillis = 400;
        java.util.List<Long> starts = java.util.Collections.synchronizedList(new java.util.ArrayList<>());
        long scheduled = System.nanoTime();
        LeaderElectionExecutors.PeriodicTask task = LeaderElectionExecutors.getInstance().schedulePeriodic("executors-spread-test",
                () -> starts.add(System.nanoTime()), periodMillis);
        try {
            Thread.sleep(periodMillis + 250);
        } finally {
            task.cancel();
            assertTrue(task.awaitIdle(1000));
        }
        assertTrue(starts.size() >= 2, "runs: " + starts.size());
        long firstMillis = TimeUnit.NANOSECONDS.toMillis(starts.get(0) - scheduled);
        long gapMillis = TimeUnit.NANOSECONDS.toMillis(starts.get(1) - starts.get(0));
        assertTrue(firstMillis < 150, "The first run must start at once, after " + firstMillis + " ms");
        assertTrue(gapMillis >= periodMillis / 2 - 50 && gapMillis <= periodMillis + 50, "The second run must follow within one period, after " + gapMillis + " ms");
    }


    /**
     * awaitIdle() waits for an active run; after cancel() no further run starts. While running, the thread carries the task name.
     *
     * @throws Exception in case of an error
     */
    @Test
    public void testCancelAndAwaitIdle() throws Exception {
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger runs = new AtomicInteger();
        StringBuilder threadName = new StringBuilder();
        LeaderElectionExecutors.PeriodicTask task = LeaderElectionExecutors.getInstance().schedulePeriodic("executors-cancel-test", () -> {
            if (runs.incrementAndGet() == 1) {
                threadName.append(Thread.currentThread().getName());
                started.countDown();
                try {
                    release.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
        }, 50);

        assertTrue(started.await(5, TimeUnit.SECONDS));
        task.cancel();
        assertFalse(task.awaitIdle(100), "The active run must still be running");
        release.countDown();
        assertTrue(task.awaitIdle(1000), "awaitIdle() must return once the run ended");

        int runsAfterCancel = runs.get();
        Thread.sleep(300);
        assertEquals(runsAfterCancel, runs.get(), "No run may start after cancel()");
        assertEquals("executors-cancel-test", threadName.toString());
    }


    /**
     * The number of library threads does not grow with the number of DATABASE electors.
     *
     * @throws Exception in case of an error
     */
    @Test
    public void testThreadsAreShared() throws Exception {
        JdbcDataSource ds = new JdbcDataSource();
        ds.setURL("jdbc:h2:mem:sharedThreads;DB_CLOSE_DELAY=-1");
        ds.setUser("sa");
        ds.setPassword("");

        int electorCount = 20;
        List<ILeaderElector> electors = new ArrayList<>();
        try {
            for (int i = 0; i < electorCount; i++) {
                DatabaseLeaderElectionConfiguration config = new DatabaseLeaderElectionConfiguration(Duration.ofSeconds(3), Duration.ofSeconds(2), Duration.ofMillis(500));
                config.setDataSource(ds);
                ILeaderElector elector = LeaderElectionFactory.getInstance().getLeaderElection(LeaderElectionStrategy.DATABASE, new LeaderElectionInformation("shared-threads-" + i), config);
                electors.add(elector);
                assertTrue(elector.elect(Duration.ofSeconds(10)));
            }
            Thread.sleep(1500);

            int libraryThreads = 0;
            for (Thread thread : Thread.getAllStackTraces().keySet()) {
                if (thread.isAlive() && (thread.getName().startsWith("leader-election-") || thread.getName().startsWith("leader-lease-monitor"))) {
                    libraryThreads++;
                }
            }
            assertTrue(libraryThreads < electorCount, electorCount + " electors must share their threads, library threads: " + libraryThreads);
        } finally {
            for (ILeaderElector elector : electors) {
                elector.close();
            }
        }
    }
}
