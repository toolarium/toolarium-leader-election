/*
 * DatabaseRecordLifecycleTest.java
 *
 * Copyright by toolarium, all rights reserved.
 */
package com.github.toolarium.leader.election.impl.database;

import static com.github.toolarium.leader.election.impl.database.DatabaseTestSupport.await;
import static com.github.toolarium.leader.election.impl.database.DatabaseTestSupport.closeQuietly;
import static com.github.toolarium.leader.election.impl.database.DatabaseTestSupport.createElector;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ch.qos.logback.classic.Level;
import com.github.toolarium.leader.election.LogCapture;
import com.github.toolarium.leader.election.dto.db.DatabaseLeaderElectionConfiguration;
import com.github.toolarium.leader.election.impl.database.dao.LeaderElectionDAO;
import com.github.toolarium.leader.election.impl.database.dao.LeaderElectorRecord;
import java.sql.Connection;
import java.sql.ResultSet;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;


/**
 * Tests the life cycle of the leader row: the delete on close() after many renewals and with an extension still running,
 * the duplicate key of two nodes inserting at the same time, and the log messages of an outage.
 *
 * @author patrick
 */
public class DatabaseRecordLifecycleTest {

    /**
     * After many renewals (more record ids than the session keeps) close() still deletes the row.
     *
     * @throws Exception in case of an error
     */
    @Test
    public void testRowIsDeletedAfterManyRenewals() throws Exception {
        FaultInjectingDataSource ds = new FaultInjectingDataSource("lifecycleManyRenewals");
        DatabaseLeaderElectorImpl elector = createElector(ds, "lifecycle-many-renewals", Duration.ofSeconds(3), Duration.ofSeconds(2), Duration.ofMillis(300));
        try {
            assertTrue(elector.elect(Duration.ofSeconds(10)));
            // about 7 renewals, more than the 4 record ids the session keeps
            Thread.sleep(2000);
            elector.close();
            assertTrue(await(() -> rowCount(ds, "lifecycle-many-renewals") == 0, 5000), "close() must delete the row");
        } finally {
            closeQuietly(elector);
        }
    }


    /**
     * An extension that still runs when close() is called writes a new record id; close() waits for it and deletes the row
     * with that id, so another node takes over at once instead of after the timeout.
     *
     * @throws Exception in case of an error
     */
    @Test
    public void testCloseWaitsForRunningExtension() throws Exception {
        FaultInjectingDataSource ds = new FaultInjectingDataSource("lifecycleCloseExtension");
        // long periods: no round renews the row during the test
        DatabaseLeaderElectorImpl elector = createElector(ds, "lifecycle-close-extension", Duration.ofSeconds(30), Duration.ofSeconds(20), Duration.ofSeconds(10));
        DatabaseLeaderElectorImpl successor = null;
        CountDownLatch extensionBlocked = new CountDownLatch(1);
        try {
            assertTrue(elector.elect(Duration.ofSeconds(10)));
            ds.blockConnections(name -> {
                if (name.startsWith(FaultInjectingDataSource.EXTEND_THREAD_PREFIX)) {
                    extensionBlocked.countDown();
                    return true;
                }
                return false;
            });
            Thread task = new Thread(() -> {
                try {
                    // interrupted by close(); the extension keeps running in the background
                    elector.runIfLeader("lifecycle-extension", lease -> lease.tryExtend());
                } catch (Exception e) {
                    // NOP
                }
            }, "test-extension-task");
            task.start();
            assertTrue(extensionBlocked.await(5, TimeUnit.SECONDS));

            // the extension commits while close() runs
            Thread releaser = new Thread(() -> {
                try {
                    Thread.sleep(500);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                ds.release();
            }, "test-release-extension");
            releaser.start();
            elector.close();
            releaser.join(5000);
            task.join(5000);

            assertEquals(0, rowCount(ds, "lifecycle-close-extension"), "close() must delete the row the extension wrote");
            successor = createElector(ds, "lifecycle-close-extension", Duration.ofSeconds(30), Duration.ofSeconds(20), Duration.ofSeconds(10));
            assertTrue(successor.elect(Duration.ofSeconds(10)), "Another node must take over at once");
        } finally {
            ds.reset();
            closeQuietly(successor);
            closeQuietly(elector);
        }
    }


    /**
     * A duplicate key of a driver that does not use SQLIntegrityConstraintViolationException (SQLState class 23, e.g.
     * PostgreSQL) counts as a lost race of two nodes inserting at the same time, not as an error.
     *
     * @throws Exception in case of an error
     */
    @Test
    public void testDuplicateKeyBySqlStateIsLostRace() throws Exception {
        FaultInjectingDataSource ds = new FaultInjectingDataSource("lifecycleDuplicateKey");
        DatabaseLeaderElectionConfiguration config = new DatabaseLeaderElectionConfiguration();
        config.setDataSource(ds);
        LeaderElectionDAO dao = new LeaderElectionDAO(config);
        try {
            dao.init();
            ds.failStatements("INSERT", "23505");
            assertEquals(-1, dao.insertLeader(new LeaderElectorRecord("lifecycle-duplicate", "instance", "user")));
        } finally {
            ds.reset();
        }
    }


    /**
     * A database outage is warned about once with a hint, further failures are not, and the recovery is reported; no log
     * message above debug has a stack trace.
     *
     * @throws Exception in case of an error
     */
    @Test
    public void testOutageIsWarnedOnce() throws Exception {
        FaultInjectingDataSource ds = new FaultInjectingDataSource("lifecycleOutage");
        DatabaseLeaderElectorImpl elector = createElector(ds, "lifecycle-outage", Duration.ofSeconds(3), Duration.ofSeconds(2), Duration.ofMillis(300));
        try (LogCapture log = new LogCapture(DatabaseLeaderElectorImpl.class)) {
            assertTrue(elector.elect(Duration.ofSeconds(10)));
            ds.failConnections(FaultInjectingDataSource::isTickThread, 0);
            assertTrue(await(() -> !log.getMessages(Level.WARN, "[lifecycle-outage] failed").isEmpty(), 15000), "The outage must be warned about");
            // further failures during the outage
            Thread.sleep(2000);
            assertEquals(1, log.getMessages(Level.WARN, "[lifecycle-outage] failed").size(), "The outage must be warned about once");
            assertTrue(log.getMessages(Level.WARN, "[lifecycle-outage] failed").get(0).contains("check the database"), "The warning must say what to do");

            ds.reset();
            assertTrue(await(() -> !log.getMessages(Level.INFO, "[lifecycle-outage] works again").isEmpty(), 15000), "The recovery must be reported");
            assertTrue(log.hasNoStackTraceFrom(Level.INFO), "Messages above debug must not have a stack trace");
        } finally {
            ds.reset();
            closeQuietly(elector);
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
