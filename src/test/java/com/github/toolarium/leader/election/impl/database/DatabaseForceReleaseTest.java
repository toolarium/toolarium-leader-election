/*
 * DatabaseForceReleaseTest.java
 *
 * Copyright by toolarium, all rights reserved.
 */
package com.github.toolarium.leader.election.impl.database;

import static com.github.toolarium.leader.election.impl.database.DatabaseTestSupport.await;
import static com.github.toolarium.leader.election.impl.database.DatabaseTestSupport.closeQuietly;
import static com.github.toolarium.leader.election.impl.database.DatabaseTestSupport.createElector;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;


/**
 * Tests the admin force-release of a DATABASE leader: the leader steps down on its next round, the successor only takes over
 * after the deadline, and the leader never renews or extends a released lease.
 *
 * @author patrick
 */
public class DatabaseForceReleaseTest {
    private static final Duration TIMEOUT = Duration.ofSeconds(4);
    private static final Duration RENEW_DEADLINE = Duration.ofSeconds(3);
    private static final Duration RETRY_PERIOD = Duration.ofMillis(300);


    /**
     * The leader steps down on its next round, nobody leads until the deadline, and afterwards another node takes over;
     * the released node does not win the lead back.
     *
     * @throws Exception in case of an error
     */
    @Test
    public void testAnotherNodeTakesOver() throws Exception {
        FaultInjectingDataSource ds = new FaultInjectingDataSource("forceReleaseLeader");
        DatabaseLeaderElectorImpl leader = createElector(ds, "force-release-leader", TIMEOUT, RENEW_DEADLINE, RETRY_PERIOD);
        DatabaseLeaderElectorImpl admin = createElector(ds, "force-release-leader", TIMEOUT, RENEW_DEADLINE, RETRY_PERIOD);
        try {
            assertTrue(leader.elect(Duration.ofSeconds(10)));
            assertFalse(admin.elect(Duration.ofSeconds(1)));

            assertTrue(admin.forceReleaseRemote(leader.getId()));
            assertTrue(await(() -> !leader.isLeader(), 2000), "The leader must step down on its next round");
            assertFalse(admin.isLeader(), "No node may lead before the deadline");

            AtomicBoolean overlap = new AtomicBoolean(false);
            assertTrue(await(() -> {
                if (leader.isLeader() && admin.isLeader()) {
                    overlap.set(true);
                }
                return leader.isLeader() || admin.isLeader();
            }, RENEW_DEADLINE.toMillis() + 3000), "A node must take the lead after the deadline");
            assertFalse(overlap.get(), "Both nodes must never lead at the same time");
            assertTrue(admin.isLeader(), "Another node must take over, not the released one");

            Thread.sleep(TIMEOUT.toMillis() + 1000);
            assertFalse(leader.isLeader(), "The released node must stay a follower");
            assertTrue(admin.isLeader());
        } finally {
            closeQuietly(admin);
            closeQuietly(leader);
        }
    }


    /**
     * Without another node, the released node takes the lead back one timeout after the deadline.
     *
     * @throws Exception in case of an error
     */
    @Test
    public void testReleasedNodeTakesTheLeadBackWhenAlone() throws Exception {
        FaultInjectingDataSource ds = new FaultInjectingDataSource("forceReleaseAlone");
        DatabaseLeaderElectorImpl leader = createElector(ds, "force-release-alone", TIMEOUT, RENEW_DEADLINE, RETRY_PERIOD);
        try {
            assertTrue(leader.elect(Duration.ofSeconds(10)));
            final long start = System.nanoTime();
            assertTrue(leader.forceReleaseRemote(leader.getId()));
            assertTrue(await(() -> !leader.isLeader(), 2000), "The leader must step down on its next round");

            long backAfterBound = RENEW_DEADLINE.toMillis() + TIMEOUT.toMillis();
            assertTrue(await(leader::isLeader, backAfterBound + 3000), "The released node must take the lead back when it is alone");
            long backAfterMillis = (System.nanoTime() - start) / 1_000_000L;
            assertTrue(backAfterMillis >= backAfterBound - 500, "The released node must stay away until one timeout after the deadline, back after " + backAfterMillis + " ms");
        } finally {
            closeQuietly(leader);
        }
    }


    /**
     * A running leader task cannot extend a force-released lease; the next round, which reads the row, steps down.
     *
     * @throws Exception in case of an error
     */
    @Test
    public void testReleasedLeaseCannotBeExtended() throws Exception {
        FaultInjectingDataSource ds = new FaultInjectingDataSource("forceReleaseExtend");
        // long retry period: the extension runs before the next round notices the release
        DatabaseLeaderElectorImpl leader = createElector(ds, "force-release-extend", Duration.ofSeconds(30), Duration.ofSeconds(20), Duration.ofSeconds(10));
        DatabaseLeaderElectorImpl admin = createElector(ds, "force-release-extend", Duration.ofSeconds(30), Duration.ofSeconds(20), Duration.ofSeconds(10));
        try {
            assertTrue(leader.elect(Duration.ofSeconds(10)));
            assertFalse(admin.elect(Duration.ofSeconds(1)));

            AtomicReference<Boolean> extended = new AtomicReference<>();
            assertTrue(leader.runIfLeader("force-release-extend", lease -> {
                assertTrue(admin.forceReleaseRemote(leader.getId()));
                extended.set(lease.tryExtend());
            }));
            assertEquals(Boolean.FALSE, extended.get(), "A released lease must not be extended");
            // the extension cannot tell a release from a concurrent renewal: the next round reads the row and steps down
            assertTrue(await(() -> !leader.isLeader(), 15000), "The elector must step down on the next round");
        } finally {
            closeQuietly(admin);
            closeQuietly(leader);
        }
    }


    /**
     * An invalid instance id is rejected; an unknown one is not found.
     *
     * @throws Exception in case of an error
     */
    @Test
    public void testForceReleaseRemoteArguments() throws Exception {
        FaultInjectingDataSource ds = new FaultInjectingDataSource("forceReleaseArguments");
        DatabaseLeaderElectorImpl elector = createElector(ds, "force-release-arguments", TIMEOUT, RENEW_DEADLINE, RETRY_PERIOD);
        try {
            assertTrue(elector.elect(Duration.ofSeconds(10)));
            assertThrows(IllegalArgumentException.class, () -> elector.forceReleaseRemote(null));
            assertThrows(IllegalArgumentException.class, () -> elector.forceReleaseRemote(" "));
            assertFalse(elector.forceReleaseRemote("unknown-instance"));
            assertTrue(elector.isLeader());
        } finally {
            closeQuietly(elector);
        }
    }


    /**
     * Without the database clock the deadline is computed from the local clock; the release still works.
     *
     * @throws Exception in case of an error
     */
    @Test
    public void testForceReleaseWithoutDatabaseClock() throws Exception {
        FaultInjectingDataSource leaderDs = new FaultInjectingDataSource("forceReleaseClock");
        FaultInjectingDataSource adminDs = new FaultInjectingDataSource("forceReleaseClock");
        DatabaseLeaderElectorImpl leader = createElector(leaderDs, "force-release-clock", TIMEOUT, RENEW_DEADLINE, RETRY_PERIOD);
        DatabaseLeaderElectorImpl admin = createElector(adminDs, "force-release-clock", TIMEOUT, RENEW_DEADLINE, RETRY_PERIOD);
        try {
            assertTrue(leader.elect(Duration.ofSeconds(10)));
            assertFalse(admin.elect(Duration.ofSeconds(1)));

            adminDs.failStatements("CURRENT_TIMESTAMP");
            assertTrue(admin.forceReleaseRemote(leader.getId()), "The release must fall back to the local clock");
            assertTrue(await(() -> !leader.isLeader(), 2000), "The leader must step down on its next round");
        } finally {
            adminDs.reset();
            closeQuietly(admin);
            closeQuietly(leader);
        }
    }


    /**
     * When the database is not reachable the release reports false and the leader keeps the lead.
     *
     * @throws Exception in case of an error
     */
    @Test
    public void testForceReleaseWhenDatabaseIsDown() throws Exception {
        FaultInjectingDataSource leaderDs = new FaultInjectingDataSource("forceReleaseDown");
        FaultInjectingDataSource adminDs = new FaultInjectingDataSource("forceReleaseDown");
        DatabaseLeaderElectorImpl leader = createElector(leaderDs, "force-release-down", TIMEOUT, RENEW_DEADLINE, RETRY_PERIOD);
        DatabaseLeaderElectorImpl admin = createElector(adminDs, "force-release-down", TIMEOUT, RENEW_DEADLINE, RETRY_PERIOD);
        try {
            assertTrue(leader.elect(Duration.ofSeconds(10)));
            assertFalse(admin.elect(Duration.ofSeconds(1)));

            adminDs.failConnections(name -> true, 0);
            assertFalse(admin.forceReleaseRemote(leader.getId()));
            adminDs.reset();

            Thread.sleep(1000);
            assertTrue(leader.isLeader(), "A failed release must not affect the leader");
        } finally {
            adminDs.reset();
            closeQuietly(admin);
            closeQuietly(leader);
        }
    }
}
