/*
 * DatabaseLeaderElectorFixesTest.java
 *
 * Copyright by toolarium, all rights reserved.
 */
package com.github.toolarium.leader.election.impl.database;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.github.toolarium.leader.election.ILeaderElector;
import com.github.toolarium.leader.election.LeaderElectionFactory;
import com.github.toolarium.leader.election.dto.LeaderElectionInformation;
import com.github.toolarium.leader.election.dto.LeaderElectionStrategy;
import com.github.toolarium.leader.election.dto.db.DatabaseLeaderElectionConfiguration;
import com.github.toolarium.leader.election.impl.database.dao.LeaderElectionDAO;
import java.io.PrintWriter;
import java.lang.reflect.Field;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import javax.sql.DataSource;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;


/**
 * Unit tests covering the fixes for the DATABASE-strategy code-review issues.
 *
 * <p>Issues covered:
 * <ul>
 *   <li>#1a – query timeout computed from retryPeriod</li>
 *   <li>#1b – pre-tick renew-deadline step-down</li>
 *   <li>#2  – close() order: tasks stopped before DB row released (successor takes over)</li>
 *   <li>#3  – forceReleaseRemote deletes the remote DB row</li>
 *   <li>#6  – elect() unblocks when the very first election round fails</li>
 *   <li>#8  – elector can be reused after close()</li>
 *   <li>#11 – close() is idempotent</li>
 *   <li>#12 – scheduler thread is a daemon with the correct name prefix</li>
 * </ul>
 *
 * @author patrick
 */
public class DatabaseLeaderElectorFixesTest {

    private static final String DB_USER = "sa";


    // -----------------------------------------------------------------------
    // Issue #1a: query timeout is computed from the retryPeriod.
    // -----------------------------------------------------------------------

    /**
     * Verify that the query timeout stored in {@link LeaderElectionDAO} equals
     * {@code ceil(retryPeriodMillis / 1000)}.
     * A missing or oversized timeout would let a hanging DB call block the election thread
     * for longer than the renew deadline, allowing another node to steal leadership while
     * the original leader still considers itself active.
     *
     * @throws Exception in case of an error
     */
    @Test
    public void testQueryTimeoutMatchesRetryPeriod() throws Exception {
        assertQueryTimeout(Duration.ofMillis(500), 1);   // ceil(0.5)  = 1
        assertQueryTimeout(Duration.ofMillis(2000), 2);  // ceil(2.0)  = 2
        assertQueryTimeout(Duration.ofMillis(2500), 3);  // ceil(2.5)  = 3
    }

    /**
     * Assert the query timeout for a given retry period.
     *
     * @param retryPeriod the retry period
     * @param expectedSeconds the expected query timeout in seconds
     * @throws Exception in case of an error
     */
    private void assertQueryTimeout(Duration retryPeriod, int expectedSeconds) throws Exception {
        JdbcDataSource ds = new JdbcDataSource();
        ds.setURL("jdbc:h2:mem:qtimeout_test;DB_CLOSE_DELAY=-1");
        ds.setUser(DB_USER);
        ds.setPassword("");
        DatabaseLeaderElectionConfiguration config = new DatabaseLeaderElectionConfiguration(
                Duration.ofSeconds(10), Duration.ofSeconds(5), retryPeriod);
        config.setDataSource(ds);

        LeaderElectionDAO dao = new LeaderElectionDAO(config);
        Field f = LeaderElectionDAO.class.getDeclaredField("queryTimeoutSeconds");
        f.setAccessible(true);
        assertEquals(expectedSeconds, (int) f.get(dao),
                "queryTimeoutSeconds must equal ceil(retryPeriodMillis/1000) for retryPeriod=" + retryPeriod);
    }


    // -----------------------------------------------------------------------
    // Issue #1b: pre-tick renew-deadline step-down.
    // -----------------------------------------------------------------------

    /**
     * Verify that the scheduler steps down as leader when the time elapsed since the last
     * successful DB renewal exceeds the renew deadline — before any DB call is attempted.
     * This guards against GC-pause / thread-starvation scenarios where the scheduler tick
     * fires late: the node must step down rather than holding a potentially expired lease.
     *
     * @throws Exception in case of an error
     */
    @Test
    public void testPreTickStepDownWhenRenewDeadlineExceeded() throws Exception {
        JdbcDataSource ds = new JdbcDataSource();
        ds.setURL("jdbc:h2:mem:pretick_test;DB_CLOSE_DELAY=-1");
        ds.setUser(DB_USER);
        ds.setPassword("");

        // retryPeriod=1 s; renewDeadline=3 s; timeout=5 s.
        // Validation (uses toSeconds): 5 > 3 >= 1 — all valid.
        DatabaseLeaderElectionConfiguration config = new DatabaseLeaderElectionConfiguration(
                Duration.ofSeconds(5), Duration.ofSeconds(3), Duration.ofSeconds(1));
        config.setDataSource(ds);

        ILeaderElector elector = LeaderElectionFactory.getInstance().getLeaderElection(
                LeaderElectionStrategy.DATABASE, new LeaderElectionInformation("pretick-group"), config);
        assertTrue(elector.elect(), "Elector must establish leadership before test can proceed");

        // Simulate a stalled scheduler: rewind lastSuccessfulRenewalAt to 4 s ago (> 3 s renewDeadline).
        // The next tick fires ~1 s from now; at that point now - lastSuccessfulRenewalAt ≈ 5 s > 3 s.
        Field f = elector.getClass().getDeclaredField("lastSuccessfulRenewalAt");
        f.setAccessible(true);
        f.setLong(elector, System.currentTimeMillis() - 4000L);

        // Poll until the elector steps down (happens on the first tick, within ~1 s).
        // We check before the SECOND tick (~2 s from now) which would re-acquire leadership.
        awaitCondition(() -> !elector.isLeader(), 2500L);
        assertFalse(elector.isLeader(),
                "Elector must step down when renew deadline is exceeded (pre-tick guard)");

        elector.close();
    }


    // -----------------------------------------------------------------------
    // Issue #1c: in-catch renew-deadline step-down when DB call hangs.
    // -----------------------------------------------------------------------

    /**
     * Verify that the scheduler steps down as leader in the {@code catch} block when a DB call
     * fails AND the elapsed time since the last successful renewal exceeds the renew deadline.
     * This path differs from the pre-tick guard (Issue #1b): here the node attempted the DB
     * call but the call took long enough (e.g. a network hang) to push the elapsed time past
     * the renew deadline before throwing.
     *
     * <p>Configuration: renewDeadline = 1 s, retryPeriod = 500 ms.
     * The {@link FailableDataSource} sleeps 600 ms before failing.
     * At tick time elapsed ≈ 500 ms (&lt;= 1000 ms) — pre-tick guard does NOT fire.
     * After the 600 ms hang, elapsed ≈ 1100 ms &gt; 1000 ms — in-catch guard fires.
     *
     * @throws Exception in case of an error
     */
    @Test
    public void testStepDownWhenDbFailsBeyondRenewDeadline() throws Exception {
        JdbcDataSource h2 = new JdbcDataSource();
        h2.setURL("jdbc:h2:mem:incatch_test;DB_CLOSE_DELAY=-1");
        h2.setUser("sa");
        h2.setPassword("");
        FailableDataSource fds = new FailableDataSource(h2, 600L);
        // toSeconds() truncation: 3 > 1 >= 0 — valid.
        DatabaseLeaderElectionConfiguration config = new DatabaseLeaderElectionConfiguration(
                Duration.ofSeconds(3), Duration.ofSeconds(1), Duration.ofMillis(500));
        config.setDataSource(fds);

        ILeaderElector elector = LeaderElectionFactory.getInstance().getLeaderElection(
                LeaderElectionStrategy.DATABASE, new LeaderElectionInformation("incatch-group"), config);
        assertTrue(elector.elect(), "Must become leader initially");

        // Activate the slow-failing DataSource after leadership is established.
        fds.setFailing(true);

        // Step-down occurs within ~1.1 s (one tick + 600 ms hang); wait up to 5 s to be resilient.
        awaitCondition(() -> !elector.isLeader(), 5000L);
        assertFalse(elector.isLeader(),
                "Elector must step down when a DB failure lasts beyond the renew deadline (in-catch path)");

        elector.close();
    }


    // -----------------------------------------------------------------------
    // Issue #2: close() stops running tasks before deleting the DB row.
    // -----------------------------------------------------------------------

    /**
     * Verify that when the leader calls {@code close()}, the DB row is deleted promptly
     * so the standby can take over on its very next tick — without waiting for the full
     * lease timeout to elapse.
     * This also implicitly validates the ordering: {@code super.close()} (interrupt tasks)
     * runs before the DB deletion so no window exists where the row is gone but a task
     * still holds a valid lease.
     *
     * @throws Exception in case of an error
     */
    @Test
    public void testCloseReleasesDbRowForSuccessor() throws Exception {
        JdbcDataSource ds = new JdbcDataSource();
        ds.setURL("jdbc:h2:mem:close_order;DB_CLOSE_DELAY=-1");
        ds.setUser(DB_USER);
        ds.setPassword("");

        ILeaderElector electorA = createElector(ds, "close-order-group", 2);
        ILeaderElector electorB = createElector(ds, "close-order-group", 2);

        assertTrue(electorA.elect(), "A must become leader");
        assertFalse(electorB.elect(Duration.ofMillis(600)), "B must not be leader while A holds it");

        electorA.close();

        // B must detect the vacant row on its next tick (~1 s retryPeriod).
        awaitCondition(electorB::isLeader, 3000L);
        assertTrue(electorB.isLeader(),
                "B must take over promptly after A's close() deletes the DB row");

        electorB.close();
    }


    // -----------------------------------------------------------------------
    // Issue #2 (ordering): DB row stays until all running tasks finish.
    // -----------------------------------------------------------------------

    /**
     * Verify the Issue #2 ordering invariant: a standby node cannot take over while the
     * current leader has a task running inside {@code runIfLeader()}.
     * The DB row must NOT be deleted until {@code close()} has stopped all running tasks
     * (via {@code super.close()}, which drains the lifecycle read-lock first).
     * If the row were deleted before the tasks stopped, the standby would win the DB
     * election while the leader's task is still executing — a split-brain window.
     *
     * @throws Exception in case of an error
     */
    @Test
    public void testSuccessorCannotTakeOverWhileTaskIsRunning() throws Exception {
        JdbcDataSource ds = new JdbcDataSource();
        ds.setURL("jdbc:h2:mem:order_safety;DB_CLOSE_DELAY=-1");
        ds.setUser(DB_USER);
        ds.setPassword("");

        ILeaderElector electorA = createElector(ds, "order-safety", 2);
        ILeaderElector electorB = createElector(ds, "order-safety", 2);

        assertTrue(electorA.elect(), "A must become leader");
        // Start B's scheduler so it can detect the vacant row after A closes.
        assertFalse(electorB.elect(Duration.ofMillis(600L)), "B must not be leader while A holds the row");

        CountDownLatch taskStarted = new CountDownLatch(1);
        CountDownLatch releaseTask = new CountDownLatch(1);

        Thread taskThread = new Thread(() -> {
            try {
                electorA.runIfLeader("hold-lock", lease -> {
                    taskStarted.countDown();
                    releaseTask.await();
                });
            } catch (Exception ignored) {
                // task may be interrupted by electorA.close() — intentionally ignored
            }
        }, "order-safety-task");
        taskThread.start();

        assertTrue(taskStarted.await(2000L, TimeUnit.MILLISECONDS), "Task must start within 2 s");

        // While the task holds the read-lock, B must remain a follower: A's scheduler is still
        // renewing the DB row and close() cannot complete until the read-lock is released.
        assertFalse(electorB.isLeader(), "B must not be leader while A's task is holding the lifecycle read-lock");

        // Release the task, then close A (deletes the DB row after tasks finish).
        releaseTask.countDown();
        taskThread.join(2000L);
        electorA.close();

        // B must take over now that the row is gone.
        awaitCondition(electorB::isLeader, 3000L);
        assertTrue(electorB.isLeader(), "B must become leader after A's close() clears the DB row");

        electorB.close();
    }


    // -----------------------------------------------------------------------
    // Issue #3: forceReleaseRemote deletes the remote leader's DB row.
    // -----------------------------------------------------------------------

    /**
     * Verify that {@link DatabaseLeaderElectorImpl#forceReleaseRemote(String)} removes the DB
     * row identified by the given instance id, enabling another node to take over immediately.
     *
     * <p>The "dead leader" row simulates a crashed remote node: it is inserted directly via the DAO
     * and has no live scheduler to re-insert it, so the test is free of any re-insertion race.
     *
     * @throws Exception in case of an error
     */
    @Test
    public void testForceReleaseRemoteDeletesDbRow() throws Exception {
        JdbcDataSource ds = new JdbcDataSource();
        ds.setURL("jdbc:h2:mem:force_release;DB_CLOSE_DELAY=-1");
        ds.setUser(DB_USER);
        ds.setPassword("");

        // Insert a "dead" leader row directly (simulates a crashed remote node with no scheduler).
        DatabaseLeaderElectionConfiguration directConfig = new DatabaseLeaderElectionConfiguration(2);
        directConfig.setDataSource(ds);
        com.github.toolarium.leader.election.impl.database.dao.LeaderElectionDAO directDAO =
                new com.github.toolarium.leader.election.impl.database.dao.LeaderElectionDAO(directConfig);
        directDAO.init();
        String deadInstanceId = "dead-node-instance-uuid";
        com.github.toolarium.leader.election.impl.database.dao.LeaderElectorRecord deadRow =
                new com.github.toolarium.leader.election.impl.database.dao.LeaderElectorRecord(
                        "force-release-group", deadInstanceId, "remote-user");
        assertEquals(1, directDAO.insertLeader(deadRow));

        // Create electorB — the "admin" elector that will force-release the dead leader.
        // elect() initializes B's DAO; it returns false because the dead leader's row is still fresh.
        ILeaderElector electorB = createElector(ds, "force-release-group", 2);
        assertFalse(electorB.elect(Duration.ofMillis(600)),
                "B must not be leader while the dead leader row is present");

        // Force-release the dead leader's row via B (simulates the admin API call on a live node).
        assertTrue(electorB instanceof DatabaseLeaderElectorImpl,
                "electorB must be a DatabaseLeaderElectorImpl for this test");
        boolean released = ((DatabaseLeaderElectorImpl) electorB).forceReleaseRemote(deadInstanceId);
        assertTrue(released, "forceReleaseRemote must return true when the row is found");

        // With the dead row gone, B's next tick should make it the leader.
        awaitCondition(electorB::isLeader, 3000L);
        assertTrue(electorB.isLeader(),
                "B must take over after the dead leader's row was force-released");

        electorB.close();
    }


    // -----------------------------------------------------------------------
    // Issue #6: elect() unblocks when the very first election round fails.
    // -----------------------------------------------------------------------

    /**
     * Verify that when the synchronous first election round in {@code initializeImplementation()}
     * fails with a DB error, {@code elect()} still returns {@code false} promptly — it must not
     * block on the internal {@code CountDownLatch} forever.
     *
     * @throws Exception in case of an error
     */
    @Test
    public void testElectUnblocksWhenFirstRoundFails() throws Exception {
        // Allow exactly 1 connection (consumed by init()), then fail all subsequent calls.
        JdbcDataSource h2 = new JdbcDataSource();
        h2.setURL("jdbc:h2:mem:elect_unblock;DB_CLOSE_DELAY=-1");
        h2.setUser("sa");
        h2.setPassword("");
        OnceThenFailDataSource ds = new OnceThenFailDataSource(h2);

        DatabaseLeaderElectionConfiguration config = new DatabaseLeaderElectionConfiguration(2);
        config.setDataSource(ds);
        ILeaderElector elector = LeaderElectionFactory.getInstance().getLeaderElection(
                LeaderElectionStrategy.DATABASE, new LeaderElectionInformation("unblock-group"), config);

        long start = System.currentTimeMillis();
        boolean result = elector.elect(Duration.ofSeconds(3));
        long elapsed = System.currentTimeMillis() - start;

        assertFalse(result, "elect() must return false when the DB fails on the first round");
        assertTrue(elapsed < 2500L,
                "elect() must return quickly when the first round fails (was " + elapsed + " ms)");

        elector.close();
    }


    // -----------------------------------------------------------------------
    // Issue #8: elector can be reused after close().
    // -----------------------------------------------------------------------

    /**
     * Verify that a {@link DatabaseLeaderElectorImpl} can complete a full lifecycle
     * (elect → lead → close) and then be re-initialized by calling {@code elect()} again.
     * Prior to the fix, {@code close()} left an {@code AtomicBoolean closing=true} that
     * was never reset, so a subsequent {@code elect()} never ran a new election round.
     *
     * @throws Exception in case of an error
     */
    @Test
    public void testReuseAfterClose() throws Exception {
        JdbcDataSource ds = new JdbcDataSource();
        ds.setURL("jdbc:h2:mem:reuse_test;DB_CLOSE_DELAY=-1");
        ds.setUser(DB_USER);
        ds.setPassword("");

        ILeaderElector elector = createElector(ds, "reuse-group", 2);

        // First lifecycle
        assertTrue(elector.elect(), "First elect() must succeed");
        assertTrue(elector.isLeader(), "Must be leader after first elect()");
        elector.close();
        assertFalse(elector.isLeader(), "Must not be leader after close()");

        // Second lifecycle on the same object instance
        assertTrue(elector.elect(), "Second elect() after close() must succeed");
        assertTrue(elector.isLeader(), "Must be leader again after re-elect");
        elector.close();
        assertFalse(elector.isLeader());
    }


    // -----------------------------------------------------------------------
    // Issue #11: close() is idempotent.
    // -----------------------------------------------------------------------

    /**
     * Verify that calling {@link ILeaderElector#close()} twice does not throw and leaves the
     * elector in a consistent non-leader state.
     * A non-idempotent close could cause double-deletion or double-shutdown exceptions
     * that are hard to reproduce and diagnose.
     *
     * @throws Exception in case of an error
     */
    @Test
    public void testIdempotentClose() throws Exception {
        JdbcDataSource ds = new JdbcDataSource();
        ds.setURL("jdbc:h2:mem:idem_test;DB_CLOSE_DELAY=-1");
        ds.setUser(DB_USER);
        ds.setPassword("");

        ILeaderElector elector = createElector(ds, "idem-group", 2);
        assertTrue(elector.elect());

        elector.close();
        assertDoesNotThrow(() -> elector.close(), "Second close() must not throw");
        assertFalse(elector.isLeader());
    }


    // -----------------------------------------------------------------------
    // Issue #12: scheduler thread is a daemon with the correct name.
    // -----------------------------------------------------------------------

    /**
     * Verify that the background scheduler thread created by {@link DatabaseLeaderElectorImpl}:
     * <ol>
     *   <li>is a <em>daemon</em> thread so the JVM can exit without waiting for it, and</li>
     *   <li>is named {@code "leader-election-db-<electionGroupName>"} for easy identification.</li>
     * </ol>
     *
     * @throws Exception in case of an error
     */
    @Test
    public void testSchedulerThreadIsDaemonWithCorrectName() throws Exception {
        String groupName = "daemon-test";
        JdbcDataSource ds = new JdbcDataSource();
        ds.setURL("jdbc:h2:mem:daemon_test;DB_CLOSE_DELAY=-1");
        ds.setUser(DB_USER);
        ds.setPassword("");

        ILeaderElector elector = createElector(ds, groupName, 2);
        assertTrue(elector.elect());
        Thread.sleep(50L); // give the scheduler thread time to start

        String expectedName = "leader-election-db-" + groupName;
        Thread found = null;
        for (Thread t : Thread.getAllStackTraces().keySet()) {
            if (t.getName().equals(expectedName)) {
                found = t;
                break;
            }
        }

        assertNotNull(found,
                "Scheduler thread '" + expectedName + "' must appear in the active thread list");
        assertTrue(found.isDaemon(),
                "Scheduler thread must be a daemon so it does not prevent JVM shutdown");

        elector.close();
    }


    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    /**
     * Create a DATABASE leader elector backed by the given DataSource.
     *
     * @param ds the data source
     * @param groupName the election group name
     * @param timeoutSeconds the lease timeout in seconds
     * @return the leader elector
     * @throws Exception in case of an error
     */
    private ILeaderElector createElector(DataSource ds, String groupName, int timeoutSeconds) throws Exception {
        DatabaseLeaderElectionConfiguration config = new DatabaseLeaderElectionConfiguration(timeoutSeconds);
        config.setDataSource(ds);
        return LeaderElectionFactory.getInstance().getLeaderElection(
                LeaderElectionStrategy.DATABASE, new LeaderElectionInformation(groupName), config);
    }

    /**
     * Poll until the condition is true or the timeout elapses.
     *
     * @param condition the condition to poll
     * @param timeoutMillis the maximum wait time in milliseconds
     * @throws InterruptedException if interrupted while waiting
     */
    private void awaitCondition(BooleanSupplier condition, long timeoutMillis) throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMillis;
        while (!condition.getAsBoolean() && System.currentTimeMillis() < deadline) {
            Thread.sleep(50L);
        }
    }


    /**
     * A {@link DataSource} wrapper that, once {@link #setFailing(boolean) setFailing(true)} is
     * called, sleeps {@code failDelayMs} milliseconds and then throws a {@link SQLException} on
     * every {@link #getConnection()} call.  This simulates a slow or hung DB connection so that
     * the elapsed time since the last successful renewal can exceed the renew deadline, triggering
     * the in-catch step-down guard (Issue #1).
     */
    private static final class FailableDataSource implements DataSource {
        private final DataSource delegate;
        private final long failDelayMs;
        private volatile boolean failing;

        /**
         * Constructor for FailableDataSource
         *
         * @param delegate the underlying DataSource
         * @param failDelayMs milliseconds to sleep before throwing when failing
         */
        FailableDataSource(DataSource delegate, long failDelayMs) {
            this.delegate = delegate;
            this.failDelayMs = failDelayMs;
            this.failing = false;
        }


        /**
         * Enable or disable the simulated failure mode.
         *
         * @param value true to start failing, false to stop
         */
        void setFailing(boolean value) {
            this.failing = value;
        }

        /**
         * @see javax.sql.DataSource#getConnection()
         */
        @Override
        public Connection getConnection() throws SQLException {
            if (failing) {
                sleepIfNeeded();
                throw new SQLException("Simulated slow DB failure");
            }
            return delegate.getConnection();
        }


        /**
         * @see javax.sql.DataSource#getConnection(String, String)
         */
        @Override
        public Connection getConnection(String username, String password) throws SQLException {
            if (failing) {
                sleepIfNeeded();
                throw new SQLException("Simulated slow DB failure");
            }
            return delegate.getConnection(username, password);
        }


        /**
         * Sleep for {@code failDelayMs} milliseconds to simulate a hung connection when failing.
         */
        private void sleepIfNeeded() {
            if (failDelayMs > 0) {
                try {
                    Thread.sleep(failDelayMs);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
        }


        /**
         * @see javax.sql.CommonDataSource#getLogWriter()
         */
        @Override
        public PrintWriter getLogWriter() throws SQLException {
            return delegate.getLogWriter();
        }


        /**
         * @see javax.sql.CommonDataSource#setLogWriter(PrintWriter)
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
         * @see java.sql.Wrapper#unwrap(Class)
         */
        @Override
        public <T> T unwrap(Class<T> iface) throws SQLException {
            return delegate.unwrap(iface);
        }


        /**
         * @see java.sql.Wrapper#isWrapperFor(Class)
         */
        @Override
        public boolean isWrapperFor(Class<?> iface) throws SQLException {
            return delegate.isWrapperFor(iface);
        }
    }


    /**
     * A {@link DataSource} that allows exactly one successful {@link #getConnection()} call
     * (consumed by {@link LeaderElectionDAO#init()}) and then simulates a persistent DB failure
     * on all subsequent calls.  Used to verify that {@code elect()} does not block forever when
     * the first election round fails with a DB error (Issue #6).
     */
    private static final class OnceThenFailDataSource implements DataSource {
        private final DataSource delegate;
        private final AtomicInteger callCount = new AtomicInteger(0);

        /**
         * Constructor for OnceThenFailDataSource
         *
         * @param delegate the underlying DataSource
         */
        OnceThenFailDataSource(DataSource delegate) {
            this.delegate = delegate;
        }


        /**
         * @see javax.sql.DataSource#getConnection()
         */
        @Override
        public Connection getConnection() throws SQLException {
            if (callCount.getAndIncrement() == 0) {
                return delegate.getConnection();
            }
            throw new SQLException("Simulated DB failure after init");
        }


        /**
         * @see javax.sql.DataSource#getConnection(String, String)
         */
        @Override
        public Connection getConnection(String username, String password) throws SQLException {
            if (callCount.getAndIncrement() == 0) {
                return delegate.getConnection(username, password);
            }
            throw new SQLException("Simulated DB failure after init");
        }


        /**
         * @see javax.sql.CommonDataSource#getLogWriter()
         */
        @Override
        public PrintWriter getLogWriter() throws SQLException {
            return delegate.getLogWriter();
        }


        /**
         * @see javax.sql.CommonDataSource#setLogWriter(PrintWriter)
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
         * @see java.sql.Wrapper#unwrap(Class)
         */
        @Override
        public <T> T unwrap(Class<T> iface) throws SQLException {
            return delegate.unwrap(iface);
        }


        /**
         * @see java.sql.Wrapper#isWrapperFor(Class)
         */
        @Override
        public boolean isWrapperFor(Class<?> iface) throws SQLException {
            return delegate.isWrapperFor(iface);
        }
    }
}
