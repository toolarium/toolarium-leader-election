/*
 * DatabaseRareJobTest.java
 *
 * Copyright by toolarium, all rights reserved.
 */
package com.github.toolarium.leader.election.impl.database;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.github.toolarium.leader.election.ILeaderElector;
import com.github.toolarium.leader.election.LeaderElectionFactory;
import com.github.toolarium.leader.election.dto.LeaderElectionInformation;
import com.github.toolarium.leader.election.dto.LeaderElectionStrategy;
import com.github.toolarium.leader.election.dto.db.DatabaseLeaderElectionConfiguration;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;


/**
 * Tests the rarely running job pattern: build, elect, run, close inside a try-with-resources block.
 *
 * @author patrick
 */
public class DatabaseRareJobTest {
    private static final String DB_USER = "sa";


    /**
     * The leader runs the task, the follower does not. After close the lead is released
     * and the next elector can take over.
     *
     * @throws Exception in case of an error
     */
    @Test
    public void testRareJobPattern() throws Exception {
        JdbcDataSource ds = new JdbcDataSource();
        ds.setURL("jdbc:h2:mem:rarejob;DB_CLOSE_DELAY=-1");
        ds.setUser(DB_USER);
        ds.setPassword("");
        String groupName = "rare-job-" + System.nanoTime();
        AtomicBoolean taskRan = new AtomicBoolean(false);

        // first run: elector wins, runs the task, closes
        try (ILeaderElector elector = LeaderElectionFactory.builder()
                .name(groupName)
                .database()
                    .dataSource(ds)
                    .build()) {
            assertTrue(elector.elect());
            boolean ran = elector.runIfLeader("rare-job", () -> taskRan.set(true));
            assertTrue(ran, "The leader must run the task");
            assertTrue(taskRan.get());
        }

        // second run after close: a new elector takes the lead
        try (ILeaderElector elector = LeaderElectionFactory.builder()
                .name(groupName)
                .database()
                    .dataSource(ds)
                    .build()) {
            assertTrue(elector.elect(), "A new elector must win after the previous one closed");
        }
    }


    /**
     * Two electors compete for the same job: only one runs the task.
     *
     * @throws Exception in case of an error
     */
    @Test
    public void testRareJobOnlyOneLeader() throws Exception {
        JdbcDataSource ds = new JdbcDataSource();
        ds.setURL("jdbc:h2:mem:rarejob_two;DB_CLOSE_DELAY=-1");
        ds.setUser(DB_USER);
        ds.setPassword("");
        String groupName = "rare-job-two-" + System.nanoTime();
        AtomicInteger taskCount = new AtomicInteger(0);

        DatabaseLeaderElectionConfiguration config1 = new DatabaseLeaderElectionConfiguration();
        config1.setDataSource(ds);
        DatabaseLeaderElectionConfiguration config2 = new DatabaseLeaderElectionConfiguration();
        config2.setDataSource(ds);

        ILeaderElector electorA = LeaderElectionFactory.getInstance().getLeaderElection(
                LeaderElectionStrategy.DATABASE, new LeaderElectionInformation(groupName), config1);
        ILeaderElector electorB = LeaderElectionFactory.getInstance().getLeaderElection(
                LeaderElectionStrategy.DATABASE, new LeaderElectionInformation(groupName), config2);
        try {
            electorA.elect();
            electorB.elect();

            electorA.runIfLeader("rare-job", () -> taskCount.incrementAndGet());
            electorB.runIfLeader("rare-job", () -> taskCount.incrementAndGet());

            assertEquals(1, taskCount.get(), "Exactly one instance must run the task");
        } finally {
            electorA.close();
            electorB.close();
        }
    }


    /**
     * The leader extends the lease with tryExtend inside runIfLeader so that a long-running
     * task does not lose leadership.
     *
     * @throws Exception in case of an error
     */
    @Test
    public void testRareJobWithTryExtend() throws Exception {
        JdbcDataSource ds = new JdbcDataSource();
        ds.setURL("jdbc:h2:mem:rarejob_extend;DB_CLOSE_DELAY=-1");
        ds.setUser(DB_USER);
        ds.setPassword("");
        String groupName = "rare-job-extend-" + System.nanoTime();

        try (ILeaderElector elector = LeaderElectionFactory.builder()
                .name(groupName)
                .timeout(Duration.ofSeconds(10))
                .database()
                    .dataSource(ds)
                    .build()) {
            assertTrue(elector.elect());
            AtomicBoolean extended = new AtomicBoolean(false);
            boolean ran = elector.runIfLeader("rare-job-extend", lease -> {
                // simulate batch work with periodic extension
                for (int i = 0; i < 3; i++) {
                    // simulate a batch step
                    Thread.sleep(50);
                    boolean ok = lease.tryExtend();
                    if (ok) {
                        extended.set(true);
                    }
                }
                lease.verify(); // confirm leadership still held at the end
            });
            assertTrue(ran, "The leader must run the task");
            assertTrue(extended.get(), "tryExtend must succeed at least once");
            assertTrue(elector.isLeader(), "The elector must still be leader after the task");
        }
    }


    /**
     * After close, isLeader returns false and runIfLeader does not run the task.
     *
     * @throws Exception in case of an error
     */
    @Test
    public void testRareJobCloseReleasesLead() throws Exception {
        JdbcDataSource ds = new JdbcDataSource();
        ds.setURL("jdbc:h2:mem:rarejob_close;DB_CLOSE_DELAY=-1");
        ds.setUser(DB_USER);
        ds.setPassword("");
        String groupName = "rare-job-close-" + System.nanoTime();

        ILeaderElector elector = LeaderElectionFactory.builder()
                .name(groupName)
                .database()
                    .dataSource(ds)
                    .build();
        assertTrue(elector.elect());
        assertTrue(elector.isLeader());

        elector.close();
        assertFalse(elector.isLeader(), "After close the elector must not be leader");
        assertFalse(elector.runIfLeader("rare-job", () -> { }),
                "runIfLeader must return false after close");
    }
}
