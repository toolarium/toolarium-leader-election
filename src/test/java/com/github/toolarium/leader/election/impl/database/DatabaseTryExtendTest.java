/*
 * DatabaseTryExtendTest.java
 *
 * Copyright by toolarium, all rights reserved.
 */
package com.github.toolarium.leader.election.impl.database;

import static com.github.toolarium.leader.election.impl.database.DatabaseTestSupport.closeQuietly;
import static com.github.toolarium.leader.election.impl.database.DatabaseTestSupport.createElector;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.github.toolarium.leader.election.exception.LeaderElectionException;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;


/**
 * Tests the error handling of {@code ILeaderLease.tryExtend()} of the DATABASE strategy.
 *
 * @author patrick
 */
public class DatabaseTryExtendTest {
    // long retry period: no election round interferes with the extension
    private static final Duration TIMEOUT = Duration.ofSeconds(30);
    private static final Duration RENEW_DEADLINE = Duration.ofSeconds(2);
    private static final Duration RETRY_PERIOD = Duration.ofSeconds(1);


    /**
     * An extension that hangs in the database returns false after the renew deadline.
     *
     * @throws Exception in case of an error
     */
    @Test
    public void testHangingExtensionReturnsFalse() throws Exception {
        FaultInjectingDataSource ds = new FaultInjectingDataSource("tryExtendHang");
        DatabaseLeaderElectorImpl elector = createElector(ds, "try-extend-hang", TIMEOUT, RENEW_DEADLINE, RETRY_PERIOD);
        try {
            assertTrue(elector.elect(Duration.ofSeconds(10)));

            ds.blockConnections(name -> name.startsWith(FaultInjectingDataSource.EXTEND_THREAD_PREFIX));
            AtomicReference<Boolean> extended = new AtomicReference<>();
            AtomicLong durationMillis = new AtomicLong();
            assertTrue(elector.runIfLeader("try-extend-hang", lease -> {
                long start = System.nanoTime();
                extended.set(lease.tryExtend());
                durationMillis.set(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start));
            }));
            assertTrue(ds.awaitBlocked(1));
            assertEquals(Boolean.FALSE, extended.get(), "A hanging extension must return false");
            assertTrue(durationMillis.get() >= RENEW_DEADLINE.toMillis() - 100 && durationMillis.get() < RENEW_DEADLINE.toMillis() + 2000,
                       "tryExtend() must give up after the renew deadline, took " + durationMillis.get() + " ms");
        } finally {
            ds.reset();
            closeQuietly(elector);
        }
    }


    /**
     * A database error during the extension is reported to the caller.
     *
     * @throws Exception in case of an error
     */
    @Test
    public void testDatabaseErrorIsReported() throws Exception {
        FaultInjectingDataSource ds = new FaultInjectingDataSource("tryExtendError");
        DatabaseLeaderElectorImpl elector = createElector(ds, "try-extend-error", TIMEOUT, RENEW_DEADLINE, RETRY_PERIOD);
        try {
            assertTrue(elector.elect(Duration.ofSeconds(10)));

            ds.failConnections(name -> name.startsWith(FaultInjectingDataSource.EXTEND_THREAD_PREFIX), 0);
            AtomicReference<Exception> error = new AtomicReference<>();
            assertTrue(elector.runIfLeader("try-extend-error", lease -> {
                error.set(assertThrows(LeaderElectionException.class, lease::tryExtend));
            }));
            assertNotNull(error.get());
            assertTrue(error.get().getMessage().contains("Simulated connection failure"), error.get().getMessage());
        } finally {
            ds.reset();
            closeQuietly(elector);
        }
    }


    /**
     * An interrupted caller gets false and keeps its interrupt flag.
     *
     * @throws Exception in case of an error
     */
    @Test
    public void testInterruptedCallerGetsFalse() throws Exception {
        FaultInjectingDataSource ds = new FaultInjectingDataSource("tryExtendInterrupt");
        DatabaseLeaderElectorImpl elector = createElector(ds, "try-extend-interrupt", TIMEOUT, RENEW_DEADLINE, RETRY_PERIOD);
        try {
            assertTrue(elector.elect(Duration.ofSeconds(10)));

            // the extension blocks, so the interrupted wait for its result is what returns
            ds.blockConnections(name -> name.startsWith(FaultInjectingDataSource.EXTEND_THREAD_PREFIX));
            AtomicReference<Boolean> extended = new AtomicReference<>();
            AtomicReference<Boolean> interrupted = new AtomicReference<>();
            assertTrue(elector.runIfLeader("try-extend-interrupt", lease -> {
                Thread.currentThread().interrupt();
                extended.set(lease.tryExtend());
                interrupted.set(Thread.currentThread().isInterrupted());
            }));
            assertEquals(Boolean.FALSE, extended.get(), "An interrupted extension must return false");
            assertEquals(Boolean.TRUE, interrupted.get(), "The interrupt flag must be kept");
            assertTrue(Thread.interrupted(), "runIfLeader() must pass the caller's interrupt on");
        } finally {
            Thread.interrupted();
            ds.reset();
            closeQuietly(elector);
        }
    }


    /**
     * A lease used after close() cannot be extended.
     *
     * @throws Exception in case of an error
     */
    @Test
    public void testExtensionAfterCloseReturnsFalse() throws Exception {
        FaultInjectingDataSource ds = new FaultInjectingDataSource("tryExtendClosed");
        DatabaseLeaderElectorImpl elector = createElector(ds, "try-extend-closed", TIMEOUT, RENEW_DEADLINE, RETRY_PERIOD);
        try {
            assertTrue(elector.elect(Duration.ofSeconds(10)));
            AtomicReference<com.github.toolarium.leader.election.ILeaderLease> leaseRef = new AtomicReference<>();
            assertTrue(elector.runIfLeader("try-extend-closed", leaseRef::set));
            elector.close();
            assertFalse(leaseRef.get().tryExtend(), "A lease of a closed elector must not be extended");
        } finally {
            closeQuietly(elector);
        }
    }
}
