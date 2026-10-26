/*
 * DatabaseSessionIsolationTest.java
 *
 * Copyright by toolarium, all rights reserved.
 */
package com.github.toolarium.leader.election.impl.database;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.github.toolarium.leader.election.ILeaderElector;
import com.github.toolarium.leader.election.LeaderElectionFactory;
import com.github.toolarium.leader.election.dto.LeaderElectionInformation;
import com.github.toolarium.leader.election.dto.LeaderElectionStrategy;
import com.github.toolarium.leader.election.dto.db.DatabaseLeaderElectionConfiguration;
import java.io.PrintWriter;
import java.lang.reflect.Field;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Logger;
import javax.sql.DataSource;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;


/**
 * Tests that a blocked election tick neither delays {@code tryExtend()} nor, after {@code close()},
 * changes the state of the next session.
 *
 * @author patrick
 */
public class DatabaseSessionIsolationTest {

    /**
     * tryExtend() must not wait behind an election tick that is blocked in a database call.
     *
     * @throws Exception in case of an error
     */
    @Test
    public void testTryExtendDoesNotWaitForRunningTick() throws Exception {
        BlockingDataSource ds = new BlockingDataSource("sessionIsolationExtend");
        DatabaseLeaderElectionConfiguration config = new DatabaseLeaderElectionConfiguration(Duration.ofSeconds(6), Duration.ofSeconds(4), Duration.ofSeconds(1));
        config.setDataSource(ds);
        ILeaderElector elector = LeaderElectionFactory.getInstance().getLeaderElection(LeaderElectionStrategy.DATABASE, new LeaderElectionInformation("session-isolation-extend"), config);
        try {
            assertTrue(elector.elect(Duration.ofSeconds(10)));

            ds.arm(false);
            assertTrue(ds.awaitBlocked(5), "An election tick must block in the data source");

            AtomicBoolean extended = new AtomicBoolean(false);
            AtomicLong durationMillis = new AtomicLong();
            elector.runIfLeader("extend", lease -> {
                long start = System.nanoTime();
                extended.set(lease.tryExtend());
                durationMillis.set(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start));
            });
            assertTrue(extended.get(), "tryExtend() must succeed while a tick is blocked");
            assertTrue(durationMillis.get() < 2000, "tryExtend() must not wait for the blocked tick, took " + durationMillis.get() + " ms");
        } finally {
            ds.release();
            elector.close();
        }
    }


    /**
     * A tick of a closed session that fails after the elector was elected again must not change the failure count, the back-off or the
     * lead of the new session.
     *
     * @throws Exception in case of an error
     */
    @Test
    public void testStaleTickErrorDoesNotAffectNewSession() throws Exception {
        BlockingDataSource ds = new BlockingDataSource("sessionIsolationStale");
        // long retry period: the new session runs one tick right after elect() and the next one only 10 s later
        DatabaseLeaderElectionConfiguration config = new DatabaseLeaderElectionConfiguration(Duration.ofSeconds(30), Duration.ofSeconds(20), Duration.ofSeconds(10));
        config.setCloseTimeout(Duration.ofSeconds(1));
        config.setDataSource(ds);
        ILeaderElector elector = LeaderElectionFactory.getInstance().getLeaderElection(LeaderElectionStrategy.DATABASE, new LeaderElectionInformation("session-isolation-stale"), config);
        try {
            // the first tick of the first session blocks; elect() gives up without a result
            ds.arm(true);
            assertFalse(elector.elect(Duration.ofMillis(500)));
            assertTrue(ds.awaitBlocked(5), "The first tick must block in the data source");

            // close() gives up waiting for the blocked tick after the close timeout
            elector.close();
            assertTrue(elector.elect(Duration.ofSeconds(10)), "The new session must take the lead");

            // the stale tick fails now, while the new session is active
            Thread staleTick = ds.getBlockedThread();
            assertNotNull(staleTick);
            ds.release();
            // the tick runs on a shared worker thread: it is finished once the thread no longer carries the tick's name
            String tickName = "leader-election-db-session-isolation-stale";
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (tickName.equals(staleTick.getName()) && System.nanoTime() < deadline) {
                Thread.sleep(20);
            }
            assertFalse(tickName.equals(staleTick.getName()), "The stale tick must finish");

            assertEquals(0, (int) readField(elector, "consecutiveFailures"), "The stale error must not count as failure of the new session");
            assertEquals(Long.MIN_VALUE, (long) readField(elector, "backoffUntil"), "The stale error must not set a back-off in the new session");
            assertTrue(elector.isLeader(), "The new session must keep the lead");
        } finally {
            ds.release();
            elector.close();
        }
    }


    /**
     * Read a private field of the database elector.
     *
     * @param elector the elector
     * @param name the field name
     * @return the field value
     * @throws Exception in case of an error
     */
    private Object readField(ILeaderElector elector, String name) throws Exception {
        Field field = DatabaseLeaderElectorImpl.class.getDeclaredField(name);
        field.setAccessible(true);
        return field.get(elector);
    }


    /**
     * A data source that, once armed, blocks the next connection request of an election tick thread until released. After the release
     * every further request of that thread fails, so its round ends with an error.
     */
    private static final class BlockingDataSource implements DataSource {
        private final JdbcDataSource delegate;
        private final AtomicBoolean armed = new AtomicBoolean(false);
        private final CountDownLatch blocked = new CountDownLatch(1);
        private final CountDownLatch gate = new CountDownLatch(1);
        private volatile boolean failAfterRelease;
        private volatile Thread blockedThread;


        /**
         * Constructor for BlockingDataSource
         *
         * @param dbName the H2 in-memory database name
         */
        BlockingDataSource(String dbName) {
            delegate = new JdbcDataSource();
            delegate.setURL("jdbc:h2:mem:" + dbName + ";DB_CLOSE_DELAY=-1");
            delegate.setUser("sa");
            delegate.setPassword("");
        }


        /**
         * Block the next connection request of an election tick thread.
         *
         * @param failAfterRelease true if the blocked thread fails all requests after the release
         */
        void arm(boolean failAfterRelease) {
            this.failAfterRelease = failAfterRelease;
            armed.set(true);
        }


        /**
         * Wait until a thread is blocked.
         *
         * @param seconds the maximum time to wait
         * @return true if a thread is blocked
         * @throws InterruptedException if interrupted
         */
        boolean awaitBlocked(long seconds) throws InterruptedException {
            return blocked.await(seconds, TimeUnit.SECONDS);
        }


        /**
         * Gets the blocked thread.
         *
         * @return the blocked thread or null
         */
        Thread getBlockedThread() {
            return blockedThread;
        }


        /**
         * Release the blocked thread.
         */
        void release() {
            gate.countDown();
        }


        /**
         * Block or fail the current thread when required.
         *
         * @throws SQLException if the current thread is the released, failing thread
         */
        private void check() throws SQLException {
            Thread current = Thread.currentThread();
            if (current == blockedThread && gate.getCount() == 0) {
                if (failAfterRelease) {
                    throw new SQLException("Simulated failure of a released tick");
                }
                return;
            }
            String name = current.getName();
            boolean tickThread = name.startsWith("leader-election-db-") && !name.startsWith("leader-election-db-extend-") && !name.startsWith("leader-election-db-close-");
            if (tickThread && armed.compareAndSet(true, false)) {
                blockedThread = current;
                blocked.countDown();
                try {
                    gate.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new SQLException("interrupted", e);
                }
                if (failAfterRelease) {
                    throw new SQLException("Simulated failure of a released tick");
                }
            }
        }


        /**
         * @see javax.sql.DataSource#getConnection()
         */
        @Override
        public Connection getConnection() throws SQLException {
            check();
            return delegate.getConnection();
        }


        /**
         * @see javax.sql.DataSource#getConnection(java.lang.String, java.lang.String)
         */
        @Override
        public Connection getConnection(String username, String password) throws SQLException {
            check();
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
        public Logger getParentLogger() throws SQLFeatureNotSupportedException {
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
