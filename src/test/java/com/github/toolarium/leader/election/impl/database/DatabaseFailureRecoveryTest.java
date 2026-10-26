/*
 * DatabaseFailureRecoveryTest.java
 *
 * Copyright by toolarium, all rights reserved.
 */
package com.github.toolarium.leader.election.impl.database;

import static com.github.toolarium.leader.election.impl.database.DatabaseTestSupport.await;
import static com.github.toolarium.leader.election.impl.database.DatabaseTestSupport.closeQuietly;
import static com.github.toolarium.leader.election.impl.database.DatabaseTestSupport.createElector;
import static com.github.toolarium.leader.election.impl.database.DatabaseTestSupport.leadershipTerm;
import static com.github.toolarium.leader.election.impl.database.DatabaseTestSupport.storedLeaderFlag;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import org.junit.jupiter.api.Test;


/**
 * Tests how a DATABASE leader behaves when its renewals fail: it steps down at the renew deadline, keeps its row as a guard
 * until the lease expired, and then takes the lead back.
 *
 * @author patrick
 */
public class DatabaseFailureRecoveryTest {

    /**
     * A renewal that hangs in the database and then fails: isLeader() is fenced at the renew deadline while the round is still
     * running, the round steps down when it fails, and after the database is back the node takes the lead again once its own
     * lease expired.
     *
     * @throws Exception in case of an error
     */
    @Test
    public void testLeaderStepsDownWhenRenewalHangsAndRecovers() throws Exception {
        FaultInjectingDataSource ds = new FaultInjectingDataSource("failureRecoveryHang");
        Duration timeout = Duration.ofSeconds(8);
        Duration renewDeadline = Duration.ofSeconds(3);
        DatabaseLeaderElectorImpl elector = createElector(ds, "failure-recovery-hang", timeout, renewDeadline, Duration.ofMillis(500));
        try {
            assertTrue(elector.elect(Duration.ofSeconds(10)));

            // the next round hangs longer than the renew deadline and then fails
            final long failStart = System.nanoTime();
            ds.failConnections(FaultInjectingDataSource::isTickThread, renewDeadline.toMillis() + 1500);
            assertTrue(await(() -> !elector.isLeader(), renewDeadline.toMillis() + 1000), "isLeader() must be fenced at the renew deadline");
            assertTrue(storedLeaderFlag(elector), "The hanging round has not stepped down yet");
            assertTrue(await(() -> !storedLeaderFlag(elector), 4000), "The failing round must step down");

            ds.reset();
            assertTrue(await(elector::isLeader, timeout.toMillis() + 5000), "The node must take the lead back once its lease expired");
            long recoveredAfterMillis = (System.nanoTime() - failStart) / 1_000_000L;
            assertTrue(recoveredAfterMillis >= timeout.toMillis() - 1000, "The row must guard the lease until it expired, recovered after " + recoveredAfterMillis + " ms");
        } finally {
            ds.reset();
            closeQuietly(elector);
        }
    }


    /**
     * When the single-statement renewal matches no row although the row still belongs to the leader, the round reads the row
     * and renews the lease with a second statement.
     *
     * @throws Exception in case of an error
     */
    @Test
    public void testRenewalFallsBackToReadingTheRow() throws Exception {
        FaultInjectingDataSource ds = new FaultInjectingDataSource("failureRecoveryFallback");
        DatabaseLeaderElectorImpl elector = createElector(ds, "failure-recovery-fallback", Duration.ofSeconds(3), Duration.ofSeconds(2), Duration.ofMillis(300));
        try {
            assertTrue(elector.elect(Duration.ofSeconds(10)));

            ds.skipRenewals(1);
            assertTrue(await(() -> ds.getSkippedRenewals() == 0, 2000));
            Thread.sleep(2500);
            assertTrue(elector.isLeader(), "The leader must renew with the second statement and keep the lead");
            assertTrue(storedLeaderFlag(elector));
        } finally {
            closeQuietly(elector);
        }
    }


    /**
     * When both renewal statements match no row, the leader steps down and takes the lead back after its lease expired.
     *
     * @throws Exception in case of an error
     */
    @Test
    public void testFailedRenewalStepsDownAndRecovers() throws Exception {
        FaultInjectingDataSource ds = new FaultInjectingDataSource("failureRecoveryStepDown");
        DatabaseLeaderElectorImpl elector = createElector(ds, "failure-recovery-step-down", Duration.ofSeconds(3), Duration.ofSeconds(2), Duration.ofMillis(300));
        try {
            assertTrue(elector.elect(Duration.ofSeconds(10)));
            final long term = leadershipTerm(elector);

            ds.skipRenewals(2);
            assertTrue(await(() -> !storedLeaderFlag(elector), 2000), "The leader must step down when the renewal fails");
            assertEquals(0, ds.getSkippedRenewals());

            assertTrue(await(elector::isLeader, 6000), "The node must take the lead back once its lease expired");
            assertTrue(leadershipTerm(elector) > term, "Taking the lead back starts a new term");
        } finally {
            closeQuietly(elector);
        }
    }
}
