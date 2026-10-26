/*
 * DatabaseRobustnessTest.java
 *
 * Copyright by toolarium, all rights reserved.
 */
package com.github.toolarium.leader.election.impl.database;

import static com.github.toolarium.leader.election.impl.database.DatabaseTestSupport.await;
import static com.github.toolarium.leader.election.impl.database.DatabaseTestSupport.closeQuietly;
import static com.github.toolarium.leader.election.impl.database.DatabaseTestSupport.createElector;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.Connection;
import java.sql.ResultSet;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;


/**
 * Tests the DATABASE strategy with a hanging database, running tasks during close(), concurrent extensions and many electors
 * on one table.
 *
 * @author patrick
 */
public class DatabaseRobustnessTest {

    /**
     * elect() without a timeout returns when the first round hangs in the database; the node leads once a round succeeds.
     *
     * @throws Exception in case of an error
     */
    @Test
    public void testElectReturnsWhenFirstRoundHangs() throws Exception {
        FaultInjectingDataSource ds = new FaultInjectingDataSource("robustnessHangingRound");
        DatabaseLeaderElectorImpl elector = createElector(ds, "robustness-hanging-round", Duration.ofSeconds(3), Duration.ofSeconds(2), Duration.ofMillis(500));
        try {
            ds.blockConnections(FaultInjectingDataSource::isTickThread);
            long start = System.nanoTime();
            assertFalse(elector.elect(), "elect() must return 'not leader' when the first round hangs");
            long durationMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
            assertTrue(durationMillis < 5000, "elect() must return after the renew deadline, took " + durationMillis + " ms");

            ds.release();
            assertTrue(await(elector::isLeader, 5000), "The node must lead once a round succeeds");
        } finally {
            ds.reset();
            closeQuietly(elector);
        }
    }


    /**
     * The row is kept while a leader task still runs after the close timeout, and deleted once it ended.
     *
     * @throws Exception in case of an error
     */
    @Test
    public void testRowIsKeptWhileTaskRuns() throws Exception {
        FaultInjectingDataSource ds = new FaultInjectingDataSource("robustnessTaskRuns");
        DatabaseLeaderElectorImpl elector = createElector(ds, "robustness-task-runs", Duration.ofSeconds(30), Duration.ofSeconds(20), Duration.ofSeconds(5));
        CountDownLatch taskStarted = new CountDownLatch(1);
        CountDownLatch releaseTask = new CountDownLatch(1);
        try {
            assertTrue(elector.elect(Duration.ofSeconds(10)));
            Thread taskThread = new Thread(() -> {
                try {
                    elector.runIfLeader("robustness-task", lease -> {
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
                } catch (Exception e) {
                    // NOP
                }
            });
            taskThread.start();
            assertTrue(taskStarted.await(5, TimeUnit.SECONDS));

            elector.close();
            assertFalse(elector.isLeader());
            assertEquals(1, rowCount(ds, "robustness-task-runs"), "The row must be kept while the task runs");

            releaseTask.countDown();
            taskThread.join(5000);
            assertTrue(await(() -> rowCount(ds, "robustness-task-runs") == 0, 5000), "The row must be deleted once the task ended");
        } finally {
            releaseTask.countDown();
            closeQuietly(elector);
        }
    }


    /**
     * Concurrent tryExtend() calls share one extension: a hanging data source does not get one connection request per call.
     *
     * @throws Exception in case of an error
     */
    @Test
    public void testConcurrentExtensionsShareOneRequest() throws Exception {
        FaultInjectingDataSource ds = new FaultInjectingDataSource("robustnessExtensions");
        DatabaseLeaderElectorImpl elector = createElector(ds, "robustness-extensions", Duration.ofSeconds(30), Duration.ofSeconds(2), Duration.ofSeconds(1));
        AtomicInteger extendRequests = new AtomicInteger();
        try {
            assertTrue(elector.elect(Duration.ofSeconds(10)));
            ds.blockConnections(name -> {
                if (name.startsWith(FaultInjectingDataSource.EXTEND_THREAD_PREFIX)) {
                    extendRequests.incrementAndGet();
                    return true;
                }
                return false;
            });

            List<Thread> threads = new ArrayList<>();
            for (int i = 0; i < 4; i++) {
                Thread thread = new Thread(() -> {
                    try {
                        elector.runIfLeader("robustness-extension-" + Thread.currentThread().getId(), lease -> lease.tryExtend());
                    } catch (Exception e) {
                        // NOP
                    }
                });
                threads.add(thread);
                thread.start();
            }
            for (Thread thread : threads) {
                thread.join(10000);
            }
            assertEquals(1, extendRequests.get(), "Concurrent extensions must share one database request");
        } finally {
            ds.reset();
            closeQuietly(elector);
        }
    }


    /**
     * The schema of a table is validated once per data source: further electors on the table do not check it again.
     *
     * @throws Exception in case of an error
     */
    @Test
    public void testSchemaIsValidatedOnce() throws Exception {
        FaultInjectingDataSource ds = new FaultInjectingDataSource("robustnessSchema");
        DatabaseLeaderElectorImpl first = createElector(ds, "robustness-schema-1", Duration.ofSeconds(3), Duration.ofSeconds(2), Duration.ofMillis(500));
        DatabaseLeaderElectorImpl second = null;
        try {
            assertTrue(first.elect(Duration.ofSeconds(10)));

            // a further schema check would fail now
            ds.failConnections(name -> name.equals("leader-election-schema-init"), 0);
            second = createElector(ds, "robustness-schema-2", Duration.ofSeconds(3), Duration.ofSeconds(2), Duration.ofMillis(500));
            assertTrue(second.elect(Duration.ofSeconds(10)), "A further elector on the validated table must not check the schema again");
        } finally {
            ds.reset();
            closeQuietly(second);
            closeQuietly(first);
        }
    }


    /**
     * Count the rows of an election.
     *
     * @param ds the data source
     * @param name the election name
     * @return the number of rows
     * @throws IllegalStateException if the rows cannot be counted
     */
    private static int rowCount(FaultInjectingDataSource ds, String name) {
        try (Connection connection = ds.getDelegate().getConnection();
             ResultSet rs = connection.createStatement().executeQuery("SELECT COUNT(*) FROM LeaderElection WHERE name = '" + name + "'")) {
            rs.next();
            return rs.getInt(1);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
