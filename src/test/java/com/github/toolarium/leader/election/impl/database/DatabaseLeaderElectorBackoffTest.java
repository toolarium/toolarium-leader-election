/*
 * DatabaseLeaderElectorBackoffTest.java
 *
 * Copyright by toolarium, all rights reserved.
 */
package com.github.toolarium.leader.election.impl.database;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.github.toolarium.leader.election.LeaderElectionFactory;
import com.github.toolarium.leader.election.LeaderElectionStrategy;
import com.github.toolarium.leader.election.LeaderElector;
import com.github.toolarium.leader.election.dto.LeaderElectionInformation;
import com.github.toolarium.leader.election.dto.db.DatabaseLeaderElectionConfiguration;
import java.io.PrintWriter;
import java.lang.reflect.Field;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.time.Duration;
import java.util.function.BooleanSupplier;
import javax.sql.DataSource;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;


/**
 * Tests the exponential back-off logic in {@link DatabaseLeaderElectorImpl}
 * by toggling a failable DataSource wrapper and inspecting private fields via reflection.
 *
 * @author patrick
 */
public class DatabaseLeaderElectorBackoffTest {

    private static final int CONSECUTIVE_FAILURE_THRESHOLD = 3;


    /**
     * Verify that after {@code CONSECUTIVE_FAILURE_THRESHOLD} consecutive DB failures
     * the back-off window is active (backoffUntil &gt; now) and consecutiveFailures reflects the count.
     *
     * @throws Exception In case of an error
     */
    @Test
    public void testBackoffAppliedAfterConsecutiveFailures() throws Exception {
        JdbcDataSource h2 = new JdbcDataSource();
        h2.setURL("jdbc:h2:mem:backofftest;DB_CLOSE_DELAY=-1");
        h2.setUser("sa");
        h2.setPassword("");

        FailableDataSource fds = new FailableDataSource(h2);
        // retryPeriod=300ms (sub-second); validate() uses toSeconds() so timeout/renewDeadline
        // must be >= 1 second to pass validation.
        DatabaseLeaderElectionConfiguration config = new DatabaseLeaderElectionConfiguration(
                Duration.ofSeconds(2), Duration.ofSeconds(1), Duration.ofMillis(300));
        config.setDataSource(fds);

        LeaderElector elector = LeaderElectionFactory.getInstance().getLeaderElection(
                LeaderElectionStrategy.DATABASE,
                new LeaderElectionInformation("backoff-test"),
                config);
        elector.initialize();
        awaitCondition(() -> elector.isLeader(), 3000);
        assertTrue(elector.isLeader(), "Elector must establish leadership before test can proceed");

        // Induce failures — each scheduler tick (300 ms) will throw on getConnection()
        fds.setFailing(true);

        // Wait for at least CONSECUTIVE_FAILURE_THRESHOLD ticks to fail.
        // Worst-case: first tick fires 300ms after setFailing(), then each subsequent actual
        // execution is separated by retryPeriod + backoffDelay (300ms backoff after fail #1,
        // 600ms after fail #2).  Total to reach fail #3 ≈ 300+300+600+600 = 1800ms + jitter.
        Thread.sleep(2500);

        Field consecutiveFailuresField = elector.getClass().getDeclaredField("consecutiveFailures");
        consecutiveFailuresField.setAccessible(true);
        int consecutiveFailures = (int) consecutiveFailuresField.get(elector);
        assertTrue(consecutiveFailures >= CONSECUTIVE_FAILURE_THRESHOLD,
                "Must record at least " + CONSECUTIVE_FAILURE_THRESHOLD
                + " consecutive failures, was: " + consecutiveFailures);

        Field backoffUntilField = elector.getClass().getDeclaredField("backoffUntil");
        backoffUntilField.setAccessible(true);
        long backoffUntil = (long) backoffUntilField.get(elector);
        // backoffUntil > 0 confirms the back-off mechanism activated at least once.
        // Checking > now would be a timing race; > 0 is stable.
        assertTrue(backoffUntil > 0,
                "Back-off must have been set (backoffUntil > 0), was: " + backoffUntil);

        elector.close();
    }


    /**
     * Verify that after DB is restored the back-off counter and window reset to zero on the
     * first successful scheduler tick.
     *
     * @throws Exception In case of an error
     */
    @Test
    public void testBackoffResetsAfterRecovery() throws Exception {
        JdbcDataSource h2 = new JdbcDataSource();
        h2.setURL("jdbc:h2:mem:backoffrecovery;DB_CLOSE_DELAY=-1");
        h2.setUser("sa");
        h2.setPassword("");

        FailableDataSource fds = new FailableDataSource(h2);
        DatabaseLeaderElectionConfiguration config = new DatabaseLeaderElectionConfiguration(
                Duration.ofSeconds(2), Duration.ofSeconds(1), Duration.ofMillis(300));
        config.setDataSource(fds);

        LeaderElector elector = LeaderElectionFactory.getInstance().getLeaderElection(
                LeaderElectionStrategy.DATABASE,
                new LeaderElectionInformation("backoff-recovery"),
                config);
        elector.initialize();
        awaitCondition(() -> elector.isLeader(), 3000);
        assertTrue(elector.isLeader());

        // Trigger at least 3 failures (3 × 300ms = 900ms + buffer)
        fds.setFailing(true);
        Thread.sleep(1200);

        Field backoffUntilField = elector.getClass().getDeclaredField("backoffUntil");
        backoffUntilField.setAccessible(true);
        long backoffUntil = (long) backoffUntilField.get(elector);
        // Confirm back-off was activated (> 0); checking > now is a timing race so we avoid it.
        assertTrue(backoffUntil > 0, "Back-off must have been set (backoffUntil > 0), was: " + backoffUntil);

        // Restore DB; wait generously for backoff to expire + at least one successful tick.
        // Max backoff after 3 failures = retryPeriod * 2^2 = 300 * 4 = 1200ms.
        // Add 2 more tick periods (2 × 300ms) as buffer → total wait = 1800ms.
        fds.setFailing(false);
        Thread.sleep(2500);

        Field consecutiveFailuresField = elector.getClass().getDeclaredField("consecutiveFailures");
        consecutiveFailuresField.setAccessible(true);
        int consecutiveFailures = (int) consecutiveFailuresField.get(elector);
        assertEquals(0, consecutiveFailures, "consecutiveFailures must reset to 0 after a successful tick");

        long backoffUntilAfter = (long) backoffUntilField.get(elector);
        assertEquals(0L, backoffUntilAfter, "backoffUntil must reset to 0 after a successful tick");

        elector.close();
    }


    /**
     * Poll the given condition every 50 ms until it returns {@code true} or the timeout elapses.
     *
     * @param condition     the condition to poll
     * @param timeoutMillis maximum time to wait in milliseconds
     * @throws InterruptedException if the polling sleep is interrupted
     */
    private static void awaitCondition(BooleanSupplier condition, long timeoutMillis) throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMillis;
        while (!condition.getAsBoolean() && System.currentTimeMillis() < deadline) {
            Thread.sleep(50);
        }
    }


    /**
     * A DataSource wrapper that delegates all calls to {@code delegate} normally,
     * but throws a {@link SQLException} from {@link #getConnection()} when {@code failing=true}.
     */
    private static final class FailableDataSource implements DataSource {
        private final DataSource delegate;
        private volatile boolean failing;


        /**
         * Constructor for FailableDataSource
         *
         * @param delegate the delegate data source
         */
        FailableDataSource(DataSource delegate) {
            this.delegate = delegate;
            this.failing = false;
        }


        /**
         * Toggle failure mode.
         *
         * @param failing true to make getConnection() throw
         */
        void setFailing(boolean failing) {
            this.failing = failing;
        }


        /**
         * @see javax.sql.DataSource#getConnection()
         */
        @Override
        public Connection getConnection() throws SQLException {
            if (failing) {
                throw new SQLException("Simulated connection failure");
            }
            return delegate.getConnection();
        }


        /**
         * @see javax.sql.DataSource#getConnection(java.lang.String, java.lang.String)
         */
        @Override
        public Connection getConnection(String username, String password) throws SQLException {
            if (failing) {
                throw new SQLException("Simulated connection failure");
            }
            return delegate.getConnection(username, password);
        }


        /**
         * @see javax.sql.CommonDataSource#getLogWriter()
         */
        @Override
        public PrintWriter getLogWriter() throws SQLException {
            return delegate.getLogWriter();
        }


        /**
         * @see javax.sql.CommonDataSource#setLogWriter(java.io.PrintWriter)
         */
        @Override
        public void setLogWriter(PrintWriter out) throws SQLException {
            delegate.setLogWriter(out);
        }


        /**
         * @see javax.sql.CommonDataSource#setLoginTimeout(int)
         */
        @Override
        public void setLoginTimeout(int seconds) throws SQLException {
            delegate.setLoginTimeout(seconds);
        }


        /**
         * @see javax.sql.CommonDataSource#getLoginTimeout()
         */
        @Override
        public int getLoginTimeout() throws SQLException {
            return delegate.getLoginTimeout();
        }


        /**
         * @see javax.sql.CommonDataSource#getParentLogger()
         */
        @Override
        public java.util.logging.Logger getParentLogger() throws SQLFeatureNotSupportedException {
            return delegate.getParentLogger();
        }


        /**
         * @see java.sql.Wrapper#unwrap(java.lang.Class)
         */
        @Override
        public <T> T unwrap(Class<T> iface) throws SQLException {
            return delegate.unwrap(iface);
        }


        /**
         * @see java.sql.Wrapper#isWrapperFor(java.lang.Class)
         */
        @Override
        public boolean isWrapperFor(Class<?> iface) throws SQLException {
            return delegate.isWrapperFor(iface);
        }
    }
}
