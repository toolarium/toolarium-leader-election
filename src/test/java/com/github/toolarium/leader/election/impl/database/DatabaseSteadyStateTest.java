/*
 * DatabaseSteadyStateTest.java
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
import java.io.PrintWriter;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.time.Duration;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Logger;
import javax.sql.DataSource;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;


/**
 * Tests the statements an election tick runs in the steady state of a leader.
 *
 * @author patrick
 */
public class DatabaseSteadyStateTest {

    /**
     * A leader renews with a single UPDATE per tick and does not read the row.
     *
     * @throws Exception in case of an error
     */
    @Test
    public void testLeaderRenewsWithoutSelect() throws Exception {
        CountingDataSource ds = new CountingDataSource("steadyStateRenew");
        DatabaseLeaderElectionConfiguration config = new DatabaseLeaderElectionConfiguration(Duration.ofSeconds(2), Duration.ofSeconds(1), Duration.ofMillis(300));
        config.setDataSource(ds);
        ILeaderElector elector = LeaderElectionFactory.getInstance().getLeaderElection(LeaderElectionStrategy.DATABASE, new LeaderElectionInformation("steady-state-renew"), config);
        try {
            assertTrue(elector.elect(Duration.ofSeconds(10)));
            ds.reset();
            Thread.sleep(1600);

            assertTrue(elector.isLeader());
            assertTrue(ds.updates.get() >= 3, "The leader must renew on every tick, updates: " + ds.updates.get());
            assertEquals(0, ds.selects.get(), "A renewing leader must not read the row");
        } finally {
            elector.close();
        }
    }


    /**
     * When the renewal matches no row (here: the row was deleted), the tick falls back to reading the row and the leader takes the lead again.
     *
     * @throws Exception in case of an error
     */
    @Test
    public void testFallbackWhenRowIsGone() throws Exception {
        CountingDataSource ds = new CountingDataSource("steadyStateFallback");
        DatabaseLeaderElectionConfiguration config = new DatabaseLeaderElectionConfiguration(Duration.ofSeconds(2), Duration.ofSeconds(1), Duration.ofMillis(300));
        config.setDataSource(ds);
        ILeaderElector elector = LeaderElectionFactory.getInstance().getLeaderElection(LeaderElectionStrategy.DATABASE, new LeaderElectionInformation("steady-state-fallback"), config);
        try {
            assertTrue(elector.elect(Duration.ofSeconds(10)));
            try (Connection connection = ds.delegate.getConnection()) {
                assertEquals(1, connection.createStatement().executeUpdate("DELETE FROM LeaderElection WHERE name = 'steady-state-fallback'"));
            }
            ds.reset();
            Thread.sleep(1000);

            assertTrue(ds.selects.get() >= 1, "A renewal without matching row must read the row");
            assertTrue(elector.isLeader(), "The leader must re-insert its row");
            try (Connection connection = ds.delegate.getConnection()) {
                assertTrue(connection.createStatement().executeQuery("SELECT 1 FROM LeaderElection WHERE name = 'steady-state-fallback'").next());
            }
        } finally {
            elector.close();
        }
        assertFalse(elector.isLeader());
    }


    /**
     * A data source that counts the SELECT and UPDATE statements on the election table prepared by election tick threads.
     */
    private static final class CountingDataSource implements DataSource {
        private final JdbcDataSource delegate;
        private final AtomicInteger selects = new AtomicInteger();
        private final AtomicInteger updates = new AtomicInteger();


        /**
         * Constructor for CountingDataSource
         *
         * @param dbName the H2 in-memory database name
         */
        CountingDataSource(String dbName) {
            delegate = new JdbcDataSource();
            delegate.setURL("jdbc:h2:mem:" + dbName + ";DB_CLOSE_DELAY=-1");
            delegate.setUser("sa");
            delegate.setPassword("");
        }


        /**
         * Reset the counters.
         */
        void reset() {
            selects.set(0);
            updates.set(0);
        }


        /**
         * Count a prepared statement of an election tick thread.
         *
         * @param sql the SQL
         */
        private void count(String sql) {
            String name = Thread.currentThread().getName();
            if (!name.startsWith("leader-election-db-") || name.startsWith("leader-election-db-extend-") || name.startsWith("leader-election-db-close-")) {
                return;
            }
            String s = sql.trim().toUpperCase(Locale.ROOT);
            if (s.startsWith("SELECT") && s.contains(" FROM LEADERELECTION ")) {
                selects.incrementAndGet();
            } else if (s.startsWith("UPDATE LEADERELECTION ")) {
                updates.incrementAndGet();
            }
        }


        /**
         * Wrap a connection so that prepared statements are counted.
         *
         * @param connection the connection
         * @return the wrapped connection
         */
        private Connection wrap(Connection connection) {
            return (Connection) Proxy.newProxyInstance(Connection.class.getClassLoader(), new Class<?>[] {Connection.class}, (proxy, method, args) -> {
                if ("prepareStatement".equals(method.getName()) && args != null && args.length > 0 && args[0] instanceof String) {
                    count((String) args[0]);
                }
                try {
                    return method.invoke(connection, args);
                } catch (InvocationTargetException e) {
                    throw e.getCause();
                }
            });
        }


        /**
         * @see javax.sql.DataSource#getConnection()
         */
        @Override
        public Connection getConnection() throws SQLException {
            return wrap(delegate.getConnection());
        }


        /**
         * @see javax.sql.DataSource#getConnection(java.lang.String, java.lang.String)
         */
        @Override
        public Connection getConnection(String username, String password) throws SQLException {
            return wrap(delegate.getConnection(username, password));
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
