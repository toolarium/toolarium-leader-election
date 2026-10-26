/*
 * DatabaseCloseDuringInitTest.java
 *
 * Copyright by toolarium, all rights reserved.
 */
package com.github.toolarium.leader.election.impl.database;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.github.toolarium.leader.election.dto.LeaderElectionInformation;
import com.github.toolarium.leader.election.dto.db.DatabaseLeaderElectionConfiguration;
import java.io.PrintWriter;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Logger;
import javax.sql.DataSource;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;


/**
 * Verifies that close() racing the first elect() of a DATABASE elector neither leaks the scheduler
 * thread nor leaves the elector in a state that can no longer be closed or re-elected.
 *
 * @author patrick
 */
public class DatabaseCloseDuringInitTest {

    /**
     * DataSource that blocks getConnection() until the gate is opened.
     */
    private static final class GatedDataSource implements DataSource {
        private final JdbcDataSource delegate;
        private final CountDownLatch entered = new CountDownLatch(1);
        private final CountDownLatch gate = new CountDownLatch(1);

        /**
         * Constructor for GatedDataSource
         *
         * @param dbName the H2 in-memory database name
         */
        GatedDataSource(String dbName) {
            delegate = new JdbcDataSource();
            delegate.setURL("jdbc:h2:mem:" + dbName + ";DB_CLOSE_DELAY=-1");
            delegate.setUser("sa");
            delegate.setPassword("");
        }


        /**
         * Signal that a connection was requested and block until the gate is opened.
         *
         * @throws SQLException if the waiting thread is interrupted
         */
        private void pass() throws SQLException {
            entered.countDown();
            try {
                gate.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new SQLException("interrupted", e);
            }
        }


        /**
         * @see javax.sql.DataSource#getConnection()
         */
        @Override
        public Connection getConnection() throws SQLException {
            pass();
            return delegate.getConnection();
        }


        /**
         * @see javax.sql.DataSource#getConnection(java.lang.String, java.lang.String)
         */
        @Override
        public Connection getConnection(String username, String password) throws SQLException {
            pass();
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
            throw new SQLFeatureNotSupportedException();
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


    /**
     * close() issued while elect() is still initializing must wait for the initialization and then
     * close cleanly; afterwards no scheduler thread is left and the elector can be elected again.
     *
     * @throws Exception in case of an error
     */
    @Test
    public void testCloseDuringInitialization() throws Exception {
        String name = "close-during-init-" + System.nanoTime();
        GatedDataSource dataSource = new GatedDataSource(name);
        DatabaseLeaderElectionConfiguration config = new DatabaseLeaderElectionConfiguration(2);
        config.setDataSource(dataSource);
        DatabaseLeaderElectorImpl elector = new DatabaseLeaderElectorImpl(new LeaderElectionInformation(name), config);

        AtomicReference<Throwable> electError = new AtomicReference<>();
        Thread electThread = new Thread(() -> {
            try {
                elector.elect();
            } catch (Throwable e) {
                electError.set(e);
            }
        }, "test-elect");
        electThread.start();
        assertTrue(dataSource.entered.await(5, TimeUnit.SECONDS), "initialization did not reach the DataSource");

        AtomicReference<Throwable> closeError = new AtomicReference<>();
        Thread closeThread = new Thread(() -> {
            try {
                elector.close();
            } catch (Throwable e) {
                closeError.set(e);
            }
        }, "test-close");
        closeThread.start();
        Thread.sleep(300);
        dataSource.gate.countDown();

        closeThread.join(TimeUnit.SECONDS.toMillis(10));
        electThread.join(TimeUnit.SECONDS.toMillis(10));
        assertFalse(closeThread.isAlive(), "close() did not return");
        assertFalse(electThread.isAlive(), "elect() without timeout hangs after a concurrent close()");
        assertNull(closeError.get());
        assertNull(electError.get());
        assertFalse(elector.isLeader());

        // The scheduler thread created by the initialization must have been shut down by close().
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (hasSchedulerThread(name) && System.nanoTime() < deadline) {
            Thread.sleep(50);
        }
        assertFalse(hasSchedulerThread(name), "scheduler thread leaked after close()");

        // The elector is still usable: it can be elected and closed again.
        assertTrue(elector.elect(Duration.ofSeconds(5)));
        elector.close();
        assertFalse(elector.isLeader());
    }


    /**
     * Check whether a DATABASE scheduler (or close) thread of the given election is still alive.
     *
     * @param name the election name
     * @return true if such a thread is alive
     */
    private static boolean hasSchedulerThread(String name) {
        for (Thread thread : Thread.getAllStackTraces().keySet()) {
            if (thread.isAlive() && thread.getName().startsWith("leader-election-db-") && thread.getName().contains(name)) {
                return true;
            }
        }
        return false;
    }
}
