/*
 * DatabaseClockTest.java
 *
 * Copyright by toolarium, all rights reserved.
 */
package com.github.toolarium.leader.election.impl.database;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.github.toolarium.leader.election.dto.db.DatabaseLeaderElectionConfiguration;
import com.github.toolarium.leader.election.impl.database.dao.LeaderElectionDAO;
import java.sql.SQLException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;


/**
 * Tests the {@link DatabaseClock}: the database time advances with the monotonic clock between reads, a newer read wins over
 * a slower older one, a failed read is retried, and the clock is shared per DataSource.
 *
 * @author patrick
 */
public class DatabaseClockTest {
    private static final long HOUR_MILLIS = TimeUnit.HOURS.toMillis(1);
    private static final String NAME = "database-clock";


    /**
     * The database time read once advances with the monotonic clock until the next read: a clock an hour ahead of the local
     * one stays an hour ahead.
     *
     * @throws Exception in case of an error
     */
    @Test
    public void testTimeAdvancesFromTheLastRead() throws Exception {
        StubDao dao = new StubDao();
        dao.offsetMillis.set(HOUR_MILLIS);
        final DatabaseClock clock = DatabaseClock.of(null);

        long first = clock.now(dao, 60000, NAME);
        assertEquals(1, dao.reads.get());
        assertTrue(Math.abs(first - System.currentTimeMillis() - HOUR_MILLIS) < 1000, "The clock must follow the database clock");

        Thread.sleep(200);
        long second = clock.now(dao, 60000, NAME);
        assertEquals(1, dao.reads.get(), "Between two refreshes the database is not queried");
        assertTrue(second - first >= 150 && second - first < 1000, "The time must advance with the monotonic clock: " + (second - first));
        assertTrue(Math.abs(clock.cachedNow() - System.currentTimeMillis() - HOUR_MILLIS) < 1000);
    }


    /**
     * Before the first successful read the local clock is used, and a failed read is tried again on the next call.
     *
     * @throws Exception in case of an error
     */
    @Test
    public void testFailedReadIsRetried() throws Exception {
        StubDao dao = new StubDao();
        dao.offsetMillis.set(HOUR_MILLIS);
        dao.failing = true;
        final DatabaseClock clock = DatabaseClock.of(null);

        long local = clock.now(dao, 60000, NAME);
        assertTrue(Math.abs(local - System.currentTimeMillis()) < 1000, "Before the first read the local clock is used");

        dao.failing = false;
        long db = clock.now(dao, 60000, NAME);
        assertEquals(2, dao.reads.get(), "A failed read must be tried again on the next call");
        assertTrue(Math.abs(db - System.currentTimeMillis() - HOUR_MILLIS) < 1000);
    }


    /**
     * A slow read that started before a newer read does not replace the newer result.
     *
     * @throws Exception in case of an error
     */
    @Test
    public void testNewerReadWins() throws Exception {
        StubDao dao = new StubDao();
        final DatabaseClock clock = DatabaseClock.of(null);
        CountDownLatch slowStarted = new CountDownLatch(1);
        CountDownLatch finishSlow = new CountDownLatch(1);
        dao.slowRead = slowStarted;
        dao.finishSlowRead = finishSlow;
        // the slow read returns a time an hour behind
        dao.slowOffsetMillis = -HOUR_MILLIS;

        Thread slow = new Thread(() -> clock.now(dao, 60000, NAME), "test-slow-clock-read");
        slow.start();
        assertTrue(slowStarted.await(5, TimeUnit.SECONDS));

        // a newer read meanwhile
        clock.requestRefresh();
        clock.now(dao, 60000, NAME);
        finishSlow.countDown();
        slow.join(5000);

        assertTrue(Math.abs(clock.cachedNow() - System.currentTimeMillis()) < 1000, "The newer read must win over the slower older one");
    }


    /**
     * Electors of one DataSource share one clock.
     */
    @Test
    public void testClockIsSharedPerDataSource() {
        JdbcDataSource first = new JdbcDataSource();
        JdbcDataSource second = new JdbcDataSource();
        assertSame(DatabaseClock.of(first), DatabaseClock.of(first));
        assertNotSame(DatabaseClock.of(first), DatabaseClock.of(second));
    }


    /**
     * A DAO whose database clock is simulated.
     */
    private static final class StubDao extends LeaderElectionDAO {
        private final AtomicInteger reads = new AtomicInteger();
        private final AtomicLong offsetMillis = new AtomicLong();
        private volatile boolean failing;
        private volatile CountDownLatch slowRead;
        private volatile CountDownLatch finishSlowRead;
        private volatile long slowOffsetMillis;


        /**
         * Constructor for StubDao
         */
        StubDao() {
            super(createConfiguration());
        }


        /**
         * @see com.github.toolarium.leader.election.impl.database.dao.LeaderElectionDAO#getDatabaseCurrentTimeMillis()
         */
        @Override
        public long getDatabaseCurrentTimeMillis() throws SQLException {
            reads.incrementAndGet();
            if (failing) {
                throw new SQLException("Simulated clock failure");
            }
            CountDownLatch started = slowRead;
            if (started != null) {
                // the first read is the slow one: it takes its time stamp before it waits
                slowRead = null;
                long time = System.currentTimeMillis() + slowOffsetMillis;
                started.countDown();
                try {
                    finishSlowRead.await(5, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                return time;
            }
            return System.currentTimeMillis() + offsetMillis.get();
        }


        /**
         * Create a configuration for the DAO.
         *
         * @return the configuration
         */
        private static DatabaseLeaderElectionConfiguration createConfiguration() {
            JdbcDataSource ds = new JdbcDataSource();
            ds.setURL("jdbc:h2:mem:databaseclock;DB_CLOSE_DELAY=-1");
            DatabaseLeaderElectionConfiguration config = new DatabaseLeaderElectionConfiguration();
            config.setDataSource(ds);
            return config;
        }
    }
}
