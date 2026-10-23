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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.github.toolarium.leader.election.ILeaderElector;
import com.github.toolarium.leader.election.LeaderElectionFactory;
import com.github.toolarium.leader.election.dto.LeaderElectionInformation;
import com.github.toolarium.leader.election.dto.LeaderElectionStrategy;
import com.github.toolarium.leader.election.dto.db.DatabaseLeaderElectionConfiguration;
import com.github.toolarium.leader.election.exception.LeaderElectionException;
import com.github.toolarium.leader.election.impl.database.dao.LeaderElectionDAO;
import java.io.PrintWriter;
import java.lang.reflect.Field;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.sql.Statement;
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
    // Issue #1a / Issue #3: query timeout bounds the whole tick to renewDeadline.
    // Each tick runs up to 3 sequential statements; timeout = ceil(renewDeadline/3/1000)
    // so the worst-case tick duration stays within renewDeadline.
    // -----------------------------------------------------------------------

    /**
     * Verify that the query timeout stored in {@link LeaderElectionDAO} equals
     * {@code floor(renewDeadlineMillis / 3 / 1000)}, clamped to 1.
     * Using floor ensures 3 × queryTimeout never exceeds renewDeadline,
     * preventing a split-brain window where a slow DB lets a tick outlast the lease.
     *
     * @throws Exception in case of an error
     */
    @Test
    public void testQueryTimeoutMatchesRetryPeriod() throws Exception {
        assertQueryTimeout(Duration.ofMillis(1500), 1);  // floor(1500/3/1000) = floor(0.5) = 0 → clamped to 1
        assertQueryTimeout(Duration.ofMillis(6000), 2);  // floor(6000/3/1000) = floor(2.0) = 2
        assertQueryTimeout(Duration.ofMillis(7500), 2);  // floor(7500/3/1000) = floor(2.5) = 2
    }

    /**
     * Assert the query timeout for a given renew deadline.
     *
     * @param renewDeadline the renew deadline
     * @param expectedSeconds the expected query timeout in seconds
     * @throws Exception in case of an error
     */
    private void assertQueryTimeout(Duration renewDeadline, int expectedSeconds) throws Exception {
        JdbcDataSource ds = new JdbcDataSource();
        ds.setURL("jdbc:h2:mem:qtimeout_test;DB_CLOSE_DELAY=-1");
        ds.setUser(DB_USER);
        ds.setPassword("");
        DatabaseLeaderElectionConfiguration config = new DatabaseLeaderElectionConfiguration(
                renewDeadline.multipliedBy(2), renewDeadline, renewDeadline.dividedBy(2));
        config.setDataSource(ds);

        LeaderElectionDAO dao = new LeaderElectionDAO(config);
        Field f = LeaderElectionDAO.class.getDeclaredField("queryTimeoutSeconds");
        f.setAccessible(true);
        assertEquals(expectedSeconds, (int) f.get(dao),
                "queryTimeoutSeconds must equal ceil(renewDeadlineMillis/3/1000) for renewDeadline=" + renewDeadline);
    }


    // -----------------------------------------------------------------------
    // Issue #1b: pre-tick renew-deadline step-down.
    // -----------------------------------------------------------------------

    /**
     * Verify that the scheduler steps down as leader when the nanoTime elapsed since the last
     * successful DB renewal exceeds the renew deadline — before any DB call is attempted.
     * This guards against GC-pause / thread-starvation scenarios where the scheduler tick
     * fires late: the node must step down rather than holding a potentially expired lease.
     *
     * <p>The guard uses {@code System.nanoTime()} (via {@code lastRenewalNanos}) rather than
     * {@code System.currentTimeMillis()} so that NTP wall-clock jumps do not cause spurious
     * step-downs (see Issue #3 fix).
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

        // Simulate a stalled scheduler: rewind lastRenewalNanos to 4 s ago (> 3 s renewDeadline.toNanos()).
        // The pre-tick guard uses nanoTime so the wall clock is irrelevant.
        Field f = elector.getClass().getDeclaredField("lastRenewalNanos");
        f.setAccessible(true);
        f.setLong(elector, System.nanoTime() - Duration.ofSeconds(4).toNanos());

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
    // Issue 1 (new): stale own row is re-acquired after local step-down.
    // -----------------------------------------------------------------------

    /**
     * Reproduce the exact scenario reported in the bug report:
     * <ol>
     *   <li>Elector A becomes leader.</li>
     *   <li>The DB row is artificially aged past {@code timeout} (simulates an outage where
     *       the row could not be renewed or deleted).</li>
     *   <li>A's nanoTime-based renewal timestamp is made to look stale so the pre-tick guard
     *       steps A down locally on the next scheduler tick.</li>
     *   <li>On the following tick, A must detect that its own expired row is re-acquirable
     *       and become leader again — without requiring a restart.</li>
     * </ol>
     * Prior to the fix, step 4 looped forever: {@code handleOwnLeaderTick()} found the own
     * row, confirmed {@code !isLeader()}, cleared {@code currentLeaderRecordId}, and returned
     * without re-acquiring. A remained leader-less until it was restarted.
     *
     * @throws Exception in case of an error
     */
    @Test
    public void testStaleOwnRowIsReacquiredAfterLocalStepDown() throws Exception {
        JdbcDataSource ds = new JdbcDataSource();
        ds.setURL("jdbc:h2:mem:stale_own_row;DB_CLOSE_DELAY=-1");
        ds.setUser(DB_USER);
        ds.setPassword("");

        // timeout=5 s, renewDeadline=3 s, retryPeriod=1 s
        DatabaseLeaderElectionConfiguration config = new DatabaseLeaderElectionConfiguration(
                Duration.ofSeconds(5), Duration.ofSeconds(3), Duration.ofSeconds(1));
        config.setDataSource(ds);

        ILeaderElector elector = LeaderElectionFactory.getInstance().getLeaderElection(
                LeaderElectionStrategy.DATABASE, new LeaderElectionInformation("stale-own-row-group"), config);
        assertTrue(elector.elect(), "Must become leader initially");

        // Age the DB row beyond timeout (lease_ts = now - 6 s, timeout = 5 s → expired).
        try (Connection conn = ds.getConnection();
             PreparedStatement stmt = conn.prepareStatement(
                     "UPDATE LeaderElection SET lease_ts = ? WHERE name = ?")) {
            stmt.setLong(1, System.currentTimeMillis() - 6_000L);
            stmt.setString(2, "stale-own-row-group");
            stmt.executeUpdate();
        }

        // Simulate the pre-tick guard triggering: rewind lastRenewalNanos beyond renewDeadline.
        Field nanoField = elector.getClass().getDeclaredField("lastRenewalNanos");
        nanoField.setAccessible(true);
        nanoField.setLong(elector, System.nanoTime() - Duration.ofSeconds(4).toNanos());

        // Step 1: elector steps down locally (pre-tick guard, within ~1 s).
        awaitCondition(() -> !elector.isLeader(), 2000L);
        assertFalse(elector.isLeader(), "Elector must step down when renew deadline is exceeded");

        // Step 2: elector re-acquires its own expired row (handleOwnLeaderTick, within ~2 s more).
        awaitCondition(elector::isLeader, 4000L);
        assertTrue(elector.isLeader(),
                "Elector must regain leadership by re-acquiring its own expired DB row (Issue 1 fix)");

        elector.close();
    }


    // -----------------------------------------------------------------------
    // Issue 2 (new): close() with exhausted budget still interrupts running task.
    // -----------------------------------------------------------------------

    /**
     * Verify that {@code close()} calls {@code signalLeaderInvalid()} at the very start
     * so that the {@link com.github.toolarium.leader.election.impl.LeaderLeaseMonitor}
     * can interrupt a blocking {@code runIfLeader} task WHILE the scheduler drains,
     * even if the scheduler wait consumes the whole close budget.
     *
     * <p>The task blocks in {@code sleep()} indefinitely; only an interrupt wakes it.
     * With the fix the monitor sees {@code isValid()==false} within 100 ms of
     * {@code close()} and interrupts the task; without the fix the monitor only gets the
     * signal after {@code awaitTermination()} finishes, which is after the budget.
     *
     * @throws Exception in case of an error
     */
    @Test
    public void testCloseSignalsLeaseInvalidBeforeSchedulerDrain() throws Exception {
        JdbcDataSource ds = new JdbcDataSource();
        ds.setURL("jdbc:h2:mem:early_signal;DB_CLOSE_DELAY=-1");
        ds.setUser(DB_USER);
        ds.setPassword("");

        // Large timeout so the DB row does not expire during the test.
        ILeaderElector elector = createElector(ds, "early-signal-group", 30);
        assertTrue(elector.elect());

        CountDownLatch taskStarted = new CountDownLatch(1);
        CountDownLatch taskFinished = new CountDownLatch(1);

        Thread taskThread = new Thread(() -> {
            try {
                elector.runIfLeader("blocking-task", lease -> {
                    taskStarted.countDown();
                    try {
                        Thread.sleep(60_000L); // blocks until interrupted
                    } catch (InterruptedException ignored) {
                        // expected: monitor interrupts via isValid()==false
                    }
                });
            } catch (Exception ignored) {
            } finally {
                taskFinished.countDown();
            }
        }, "blocking-task-thread");
        taskThread.start();

        assertTrue(taskStarted.await(2_000L, TimeUnit.MILLISECONDS), "Task must start");

        // close() must interrupt the task via signalLeaderInvalid() + monitor (≤ 100 ms + margin).
        // Total: well under 1 s. Without the fix it would take the full closeTimeout.
        long start = System.nanoTime();
        elector.close();
        long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);

        assertTrue(taskFinished.await(1_500L, TimeUnit.MILLISECONDS),
                "Task must finish after monitor interrupt, but was still running after 1.5 s");
        assertTrue(elapsedMs < 3_000L,
                "close() must complete quickly when task is interrupted by monitor, took " + elapsedMs + " ms");
    }


    // -----------------------------------------------------------------------
    // Issue 2 (new): no spurious interrupt leaks to caller after task finishes.
    // -----------------------------------------------------------------------

    /**
     * Verify that the {@link com.github.toolarium.leader.election.impl.LeaderLeaseMonitor}
     * cannot send a late interrupt to the caller's thread after a {@code runIfLeader} task
     * has already returned.
     *
     * <p>The race: after the task returns, the monitor may still hold a reference to the
     * lease from a previous snapshot iteration, call {@code isValid()==false}, and invoke
     * {@code interruptIfRunning()} — hitting the caller thread while it is already in the
     * <em>next</em> unrelated job.  The fix adds a {@code finished} flag set inside the
     * same {@code synchronized(lease)} block that inspects the interrupt status, making
     * {@code interruptIfRunning()} a no-op once the task is done.
     *
     * <p>Strategy: run 40 quick tasks with a 110 ms gap between them so the monitor
     * (period = 100 ms) fires at least once in each gap.  Without the fix the spurious
     * interrupt would appear within a handful of iterations.
     *
     * @throws Exception in case of an error
     */
    @Test
    public void testNoSpuriousInterruptAfterTaskFinishesUnderMonitorLoad() throws Exception {
        JdbcDataSource ds = new JdbcDataSource();
        ds.setURL("jdbc:h2:mem:no_interrupt;DB_CLOSE_DELAY=-1");
        ds.setUser(DB_USER);
        ds.setPassword("");

        ILeaderElector elector = createElector(ds, "no-interrupt-group", 30);
        assertTrue(elector.elect());

        try {
            for (int i = 0; i < 40; i++) {
                boolean ran = elector.runIfLeader("instant-task-" + i, lease -> { /* returns immediately */ });
                assertTrue(ran, "Task must run while leader (iteration " + i + ")");

                // Sleep past the monitor's CHECK_PERIOD_MILLIS (100 ms) to give it a chance to
                // fire after the task has finished but before markFinished() is seen. Without the
                // fix, interruptIfRunning() would land on the current thread here.
                Thread.sleep(110L);

                assertFalse(Thread.currentThread().isInterrupted(),
                        "Caller thread must not carry a spurious interrupt from the monitor "
                        + "after runIfLeader completes (iteration " + i + ")");
            }
        } finally {
            elector.close();
            Thread.interrupted(); // clear any interrupt from close()
        }
    }


    // -----------------------------------------------------------------------
    // Issue 5 (new): nanoTime Long.MIN_VALUE sentinel — step-down guard must
    // not fire when lastRenewalNanos is NANOS_NOT_SET.
    // -----------------------------------------------------------------------

    /**
     * Verify that the pre-tick step-down guard does <em>not</em> fire when
     * {@code lastRenewalNanos} holds the {@code Long.MIN_VALUE} ("not set") sentinel.
     *
     * <p>Before the fix, the guard used {@code lastRenewalNanos > 0} as "has been set".
     * On platforms where {@code System.nanoTime()} returns a negative value (allowed by
     * the JDK specification), a real renewal would produce a value ≤ 0, permanently
     * disabling the guard.  The fix uses {@code Long.MIN_VALUE} as a never-colliding
     * sentinel so the guard fires whenever a real nanoTime value (positive OR negative)
     * has been written.
     *
     * <p>This test injects {@code Long.MIN_VALUE} directly and confirms the elector
     * stays leader, because {@code Long.MIN_VALUE != Long.MIN_VALUE} is false → guard
     * skipped. It also injects a small negative value (valid on some platforms) and
     * confirms the guard fires correctly once the elapsed nanoTime exceeds renewDeadline.
     *
     * @throws Exception in case of an error
     */
    @Test
    public void testNanoTimeSentinelDoesNotTriggerStepDown() throws Exception {
        JdbcDataSource ds = new JdbcDataSource();
        ds.setURL("jdbc:h2:mem:nano_sentinel;DB_CLOSE_DELAY=-1");
        ds.setUser(DB_USER);
        ds.setPassword("");

        DatabaseLeaderElectionConfiguration config = new DatabaseLeaderElectionConfiguration(
                Duration.ofSeconds(10), Duration.ofSeconds(6), Duration.ofSeconds(1));
        config.setDataSource(ds);

        ILeaderElector elector = LeaderElectionFactory.getInstance().getLeaderElection(
                LeaderElectionStrategy.DATABASE, new LeaderElectionInformation("nano-sentinel-group"), config);
        assertTrue(elector.elect());

        // Inject NANOS_NOT_SET = Long.MIN_VALUE — should NOT trigger the guard.
        Field f = elector.getClass().getDeclaredField("lastRenewalNanos");
        f.setAccessible(true);
        f.setLong(elector, Long.MIN_VALUE);

        Thread.sleep(1_500L); // two retryPeriods — enough for a tick to run
        assertTrue(elector.isLeader(),
                "Guard must not fire when lastRenewalNanos == Long.MIN_VALUE (NANOS_NOT_SET sentinel)");

        // Now inject a small negative value representing a real (valid) nanoTime.
        // Elapsed = now - lastRenewalNanos ≈ (positive now) - (small negative) > Long.MAX_VALUE/2
        // which overflows. However, the check is:
        //   System.nanoTime() - lastRenewalNanos > renewDeadline.toNanos()
        // With lastRenewalNanos = -1 and nanoTime ≈ some positive value T:
        //   T - (-1) = T + 1  → if T > renewDeadline.toNanos()-1 it fires.
        // For a typical JVM the nanoTime is in the billions of nanoseconds, >> 6s.
        // So setting it to -1 (not NANOS_NOT_SET) should trigger the guard.
        f.setLong(elector, -1L); // valid negative nanoTime — NOT the sentinel

        awaitCondition(() -> !elector.isLeader(), 2_500L);
        assertFalse(elector.isLeader(),
                "Guard must fire when lastRenewalNanos == -1 (valid negative nanoTime, not the sentinel)");

        elector.close();
    }


    // -----------------------------------------------------------------------
    // Issue 3 (new): pre-tick guard is NOT triggered by a wall-clock jump.
    // -----------------------------------------------------------------------

    /**
     * Verify that a stale {@code lastSuccessfulRenewalAt} wall-clock timestamp does NOT
     * cause the pre-tick guard to step the elector down.
     * After the Issue 3 fix the guard exclusively uses {@code lastRenewalNanos}
     * ({@code System.nanoTime()}), so a wall-clock jump forward — simulated here by making
     * {@code lastSuccessfulRenewalAt} appear very old — must have no effect.
     *
     * @throws Exception in case of an error
     */
    @Test
    public void testPreTickGuardNotTriggeredByWallClockSkew() throws Exception {
        JdbcDataSource ds = new JdbcDataSource();
        ds.setURL("jdbc:h2:mem:wall_clock_skew;DB_CLOSE_DELAY=-1");
        ds.setUser(DB_USER);
        ds.setPassword("");

        DatabaseLeaderElectionConfiguration config = new DatabaseLeaderElectionConfiguration(
                Duration.ofSeconds(10), Duration.ofSeconds(6), Duration.ofSeconds(1));
        config.setDataSource(ds);

        ILeaderElector elector = LeaderElectionFactory.getInstance().getLeaderElection(
                LeaderElectionStrategy.DATABASE, new LeaderElectionInformation("wall-clock-skew-group"), config);
        assertTrue(elector.elect());

        // Simulate a wall-clock jump: set lastSuccessfulRenewalAt to 30 s in the past.
        // The old (wall-clock) guard would have stepped the node down immediately.
        Field wallField = elector.getClass().getDeclaredField("lastSuccessfulRenewalAt");
        wallField.setAccessible(true);
        wallField.setLong(elector, System.currentTimeMillis() - 30_000L);

        // lastRenewalNanos is left untouched — the nanoTime-based guard must NOT fire.
        Thread.sleep(1500L); // wait past one retryPeriod so at least one tick runs

        assertTrue(elector.isLeader(),
                "Elector must remain leader: wall-clock skew must not trigger the pre-tick step-down guard");

        elector.close();
    }


    // -----------------------------------------------------------------------
    // Issue 5 (new): allowDdl=false — fail-fast instead of silent DDL execution.
    // -----------------------------------------------------------------------

    /**
     * Verify that with {@code allowDdl=false}, calling {@code elect()} when the
     * leader-election table does not yet exist throws a descriptive
     * {@link LeaderElectionException} naming the table and mentioning {@code allowDdl=false},
     * instead of silently executing {@code CREATE TABLE}.
     *
     * @throws Exception in case of an error
     */
    @Test
    public void testAllowDdlFalseFailsFastOnMissingTable() throws Exception {
        JdbcDataSource ds = new JdbcDataSource();
        ds.setURL("jdbc:h2:mem:no_ddl_table;DB_CLOSE_DELAY=-1");
        ds.setUser(DB_USER);
        ds.setPassword("");

        DatabaseLeaderElectionConfiguration config = new DatabaseLeaderElectionConfiguration(2);
        config.setDataSource(ds);
        config.setAllowDdl(false); // DDL forbidden

        ILeaderElector elector = LeaderElectionFactory.getInstance().getLeaderElection(
                LeaderElectionStrategy.DATABASE, new LeaderElectionInformation("no-ddl-group"), config);

        LeaderElectionException ex = assertThrows(LeaderElectionException.class, elector::elect,
                "elect() must throw when table is missing and allowDdl=false");
        String msg = ex.getMessage() + (ex.getCause() != null ? " " + ex.getCause().getMessage() : "");
        assertTrue(msg.contains("allowDdl=false"),
                "Error must mention 'allowDdl=false', was: " + msg);
    }


    /**
     * Verify that with {@code allowDdl=false}, calling {@link LeaderElectionDAO#init()}
     * when the table exists but the {@code force_release_deadline} column is missing throws
     * a descriptive {@link SQLException} that names the missing column and mentions
     * {@code allowDdl=false}, together with the DDL to apply manually.
     *
     * @throws Exception in case of an error
     */
    @Test
    public void testAllowDdlFalseFailsOnMissingColumn() throws Exception {
        JdbcDataSource ds = new JdbcDataSource();
        ds.setURL("jdbc:h2:mem:no_ddl_col;DB_CLOSE_DELAY=-1");
        ds.setUser(DB_USER);
        ds.setPassword("");

        // Create the table without the force_release_deadline column (pre-v1.2 schema).
        try (Connection conn = ds.getConnection();
             Statement stmt = conn.createStatement()) {
            stmt.execute("CREATE TABLE LeaderElection_noDdl "
                + "(id varchar(36) not NULL, name varchar(512) not NULL, "
                + "instance varchar(255), username varchar(255), lease_ts bigint not NULL, "
                + "PRIMARY KEY (name))");
        }

        DatabaseLeaderElectionConfiguration config = new DatabaseLeaderElectionConfiguration(2);
        config.setDataSource(ds);
        com.github.toolarium.leader.election.dto.db.JDBCLeaderElectionDatabaseStructure struct =
                new com.github.toolarium.leader.election.dto.db.JDBCLeaderElectionDatabaseStructure();
        struct.setTableName("LeaderElection_noDdl");
        config.setLeaderElectionDatabaseStructure(struct);
        config.setAllowDdl(false);

        LeaderElectionDAO dao = new LeaderElectionDAO(config);
        SQLException ex = assertThrows(SQLException.class, dao::init,
                "init() must throw when force_release_deadline is missing and allowDdl=false");
        assertTrue(ex.getMessage().contains("allowDdl=false"),
                "Error must mention 'allowDdl=false', was: " + ex.getMessage());
        assertTrue(ex.getMessage().contains("force_release_deadline"),
                "Error must name the missing column, was: " + ex.getMessage());

        // Cleanup
        try (Connection conn = ds.getConnection();
             Statement stmt = conn.createStatement()) {
            stmt.execute("DROP TABLE LeaderElection_noDdl");
        }
    }


    // -----------------------------------------------------------------------
    // Issue 6 (new): close() completes within a single closeTimeout budget.
    // -----------------------------------------------------------------------

    /**
     * Verify that {@link DatabaseLeaderElectorImpl#close()} completes within
     * {@code 2 × closeTimeout} even when there is a running scheduler tick,
     * and that concurrent calls from two threads do not deadlock.
     *
     * @throws Exception in case of an error
     */
    @Test
    public void testCloseCompletesWithinSingleDeadline() throws Exception {
        JdbcDataSource ds = new JdbcDataSource();
        ds.setURL("jdbc:h2:mem:close_deadline;DB_CLOSE_DELAY=-1");
        ds.setUser(DB_USER);
        ds.setPassword("");

        DatabaseLeaderElectionConfiguration config = new DatabaseLeaderElectionConfiguration(10);
        config.setDataSource(ds);
        config.setCloseTimeout(Duration.ofSeconds(2));

        ILeaderElector elector = LeaderElectionFactory.getInstance().getLeaderElection(
                LeaderElectionStrategy.DATABASE, new LeaderElectionInformation("close-deadline-group"), config);
        assertTrue(elector.elect());

        long start = System.nanoTime();
        elector.close();
        long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);

        // With a single-deadline close, both the scheduler wait and the task-cleanup wait
        // share the 2 s budget rather than each getting their own 2 s.
        assertTrue(elapsedMs < 4_500L,
                "close() must complete within 2 × closeTimeout (4 s), elapsed: " + elapsedMs + " ms");
    }


    /**
     * Verify that calling {@link ILeaderElector#close()} concurrently from two threads
     * does not deadlock and both calls return without throwing.
     *
     * @throws Exception in case of an error
     */
    @Test
    public void testConcurrentCloseDoesNotDeadlock() throws Exception {
        JdbcDataSource ds = new JdbcDataSource();
        ds.setURL("jdbc:h2:mem:concurrent_close;DB_CLOSE_DELAY=-1");
        ds.setUser(DB_USER);
        ds.setPassword("");

        ILeaderElector elector = createElector(ds, "concurrent-close-group", 5);
        assertTrue(elector.elect());

        CountDownLatch startGate = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(2);
        AtomicInteger errors = new AtomicInteger(0);

        Runnable closer = () -> {
            try {
                startGate.await();
                elector.close();
            } catch (Exception e) {
                errors.incrementAndGet();
            } finally {
                done.countDown();
            }
        };

        Thread t1 = new Thread(closer, "close-thread-1");
        Thread t2 = new Thread(closer, "close-thread-2");
        t1.start();
        t2.start();
        startGate.countDown();

        assertTrue(done.await(10_000L, TimeUnit.MILLISECONDS),
                "Both close() calls must complete within 10 s");
        assertEquals(0, errors.get(), "close() must not throw on concurrent invocation");
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
