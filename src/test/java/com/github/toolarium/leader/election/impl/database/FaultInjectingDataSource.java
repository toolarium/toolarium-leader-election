/*
 * FaultInjectingDataSource.java
 *
 * Copyright by toolarium, all rights reserved.
 */
package com.github.toolarium.leader.election.impl.database;

import java.io.PrintWriter;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.sql.Statement;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Predicate;
import java.util.logging.Logger;
import javax.sql.DataSource;
import org.h2.jdbcx.JdbcDataSource;


/**
 * A H2 data source that injects database faults for selected threads:
 * <ul>
 *   <li>failing connection requests, optionally after a delay,</li>
 *   <li>blocking connection requests until released,</li>
 *   <li>failing statements that contain a given SQL fragment,</li>
 *   <li>lease renewals of election ticks that report "no row updated" without running.</li>
 * </ul>
 *
 * @author patrick
 */
final class FaultInjectingDataSource implements DataSource {
    /** The thread name prefix of the election ticks. */
    static final String TICK_THREAD_PREFIX = "leader-election-db-";
    /** The thread name prefix of the lease extensions. */
    static final String EXTEND_THREAD_PREFIX = "leader-election-db-extend-";
    private static final String RENEW_SQL = "SET id = ?, lease_ts = ? WHERE";

    private final JdbcDataSource delegate;
    private final AtomicInteger skippedRenewals = new AtomicInteger();
    private volatile Predicate<String> failingThreads;
    private volatile long failDelayMillis;
    private volatile Predicate<String> blockingThreads;
    private volatile CountDownLatch blocked;
    private volatile CountDownLatch gate;
    private volatile String failingSql;
    private volatile String failingSqlState;


    /**
     * Constructor for FaultInjectingDataSource
     *
     * @param dbName the H2 in-memory database name
     */
    FaultInjectingDataSource(String dbName) {
        delegate = new JdbcDataSource();
        delegate.setURL("jdbc:h2:mem:" + dbName + ";DB_CLOSE_DELAY=-1");
        delegate.setUser("sa");
        delegate.setPassword("");
        reset();
    }


    /**
     * Gets the plain H2 data source without faults.
     *
     * @return the H2 data source
     */
    JdbcDataSource getDelegate() {
        return delegate;
    }


    /**
     * Remove all faults and release blocked threads.
     */
    void reset() {
        failingThreads = name -> false;
        failDelayMillis = 0;
        failingSql = null;
        failingSqlState = null;
        skippedRenewals.set(0);
        release();
        blockingThreads = name -> false;
        blocked = new CountDownLatch(1);
        gate = new CountDownLatch(1);
    }


    /**
     * Let the connection requests of the matching threads fail.
     *
     * @param threads the thread name filter
     * @param delayMillis the delay before the request fails
     */
    void failConnections(Predicate<String> threads, long delayMillis) {
        this.failDelayMillis = delayMillis;
        this.failingThreads = threads;
    }


    /**
     * Block the connection requests of the matching threads until {@link #release()}.
     *
     * @param threads the thread name filter
     */
    void blockConnections(Predicate<String> threads) {
        this.blockingThreads = threads;
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
     * Release the blocked threads and stop blocking.
     */
    void release() {
        blockingThreads = name -> false;
        CountDownLatch g = gate;
        if (g != null) {
            g.countDown();
        }
    }


    /**
     * Let every statement fail that contains the given SQL fragment.
     *
     * @param sqlFragment the SQL fragment, or null for none
     */
    void failStatements(String sqlFragment) {
        failStatements(sqlFragment, null);
    }


    /**
     * Let every statement fail that contains the given SQL fragment, with the given SQLState (e.g. "23505" for a duplicate key
     * of a driver that does not use SQLIntegrityConstraintViolationException).
     *
     * @param sqlFragment the SQL fragment, or null for none
     * @param sqlState the SQLState, or null
     */
    void failStatements(String sqlFragment, String sqlState) {
        this.failingSqlState = sqlState;
        this.failingSql = sqlFragment;
    }


    /**
     * The next lease renewals of election ticks report "no row updated" without running.
     *
     * @param count the number of renewals
     */
    void skipRenewals(int count) {
        skippedRenewals.set(count);
    }


    /**
     * Gets the number of renewals that are still to be skipped.
     *
     * @return the number of renewals
     */
    int getSkippedRenewals() {
        return skippedRenewals.get();
    }


    /**
     * Test if the current thread is an election tick.
     *
     * @return true if the current thread is an election tick
     */
    static boolean isTickThread() {
        return isTickThread(Thread.currentThread().getName());
    }


    /**
     * Test if a thread name belongs to an election tick.
     *
     * @param name the thread name
     * @return true if the name belongs to an election tick
     */
    static boolean isTickThread(String name) {
        return name.startsWith(TICK_THREAD_PREFIX) && !name.startsWith(EXTEND_THREAD_PREFIX);
    }


    /**
     * Apply the connection faults to the current thread.
     *
     * @throws SQLException if the connection request fails
     */
    private void check() throws SQLException {
        String name = Thread.currentThread().getName();
        if (blockingThreads.test(name)) {
            CountDownLatch g = gate;
            blocked.countDown();
            try {
                g.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new SQLException("interrupted", e);
            }
        }
        if (failingThreads.test(name)) {
            if (failDelayMillis > 0) {
                try {
                    Thread.sleep(failDelayMillis);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            throw new SQLException("Simulated connection failure");
        }
    }


    /**
     * Fail if the SQL contains the failing fragment.
     *
     * @param sql the SQL
     * @throws SQLException if the statement fails
     */
    private void checkSql(String sql) throws SQLException {
        String fragment = failingSql;
        if (fragment != null && sql != null && sql.contains(fragment)) {
            throw new SQLException("Simulated statement failure", failingSqlState);
        }
    }


    /**
     * Invoke a method on the delegate and unwrap the exception.
     *
     * @param target the target
     * @param method the method
     * @param args the arguments
     * @return the result
     * @throws Exception the exception of the method
     */
    private static Object invoke(Object target, java.lang.reflect.Method method, Object[] args) throws Exception {
        try {
            return method.invoke(target, args);
        } catch (InvocationTargetException e) {
            Throwable cause = e.getCause();
            if (cause instanceof Exception) {
                throw (Exception) cause;
            }
            if (cause instanceof Error) {
                throw (Error) cause;
            }
            throw e;
        }
    }


    /**
     * Wrap a connection to apply the statement faults.
     *
     * @param connection the connection
     * @return the wrapped connection
     */
    private Connection wrap(Connection connection) {
        return (Connection) Proxy.newProxyInstance(Connection.class.getClassLoader(), new Class<?>[] {Connection.class}, (proxy, method, args) -> {
            if ("prepareStatement".equals(method.getName()) && args != null && args.length > 0 && args[0] instanceof String) {
                String sql = (String) args[0];
                checkSql(sql);
                return wrap((PreparedStatement) invoke(connection, method, args), sql);
            }
            if ("createStatement".equals(method.getName())) {
                return wrap((Statement) invoke(connection, method, args));
            }
            return invoke(connection, method, args);
        });
    }


    /**
     * Wrap a prepared statement to skip lease renewals.
     *
     * @param statement the statement
     * @param sql the SQL of the statement
     * @return the wrapped statement
     */
    private PreparedStatement wrap(PreparedStatement statement, String sql) {
        return (PreparedStatement) Proxy.newProxyInstance(PreparedStatement.class.getClassLoader(), new Class<?>[] {PreparedStatement.class}, (proxy, method, args) -> {
            if ("executeUpdate".equals(method.getName()) && (args == null || args.length == 0) && sql.contains(RENEW_SQL) && isTickThread()) {
                if (skippedRenewals.getAndUpdate(n -> Math.max(0, n - 1)) > 0) {
                    return 0;
                }
            }
            return invoke(statement, method, args);
        });
    }


    /**
     * Wrap a statement to apply the statement faults.
     *
     * @param statement the statement
     * @return the wrapped statement
     */
    private Statement wrap(Statement statement) {
        return (Statement) Proxy.newProxyInstance(Statement.class.getClassLoader(), new Class<?>[] {Statement.class}, (proxy, method, args) -> {
            if (method.getName().startsWith("execute") && args != null && args.length > 0 && args[0] instanceof String) {
                checkSql((String) args[0]);
            }
            return invoke(statement, method, args);
        });
    }


    /**
     * @see javax.sql.DataSource#getConnection()
     */
    @Override
    public Connection getConnection() throws SQLException {
        check();
        return wrap(delegate.getConnection());
    }


    /**
     * @see javax.sql.DataSource#getConnection(java.lang.String, java.lang.String)
     */
    @Override
    public Connection getConnection(String username, String password) throws SQLException {
        check();
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
