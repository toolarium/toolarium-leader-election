/*
 * DatabaseClock.java
 *
 * Copyright by toolarium, all rights reserved.
 */
package com.github.toolarium.leader.election.impl.database;

import com.github.toolarium.leader.election.impl.database.dao.LeaderElectionDAO;
import java.util.Collections;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


/**
 * The clock of a database, shared by all electors of a {@link DataSource}, so that each elector does not query the database
 * clock on its own. The database time is read every {@value #SYNC_TICKS} retry periods; in between it advances with the
 * monotonic clock ({@link System#nanoTime()}), so a step of the local wall clock (NTP, a resumed VM) does not change it.
 *
 * <p>The database time is taken as the time in the middle of the query, and a slower query that ends after a newer one
 * does not replace it. If a refresh fails, the last database time keeps advancing (or the local clock is used before the
 * first successful refresh), so a round can still proceed; if the database is unreachable, the next statement of the round
 * fails anyway.
 *
 * @author patrick
 */
final class DatabaseClock {
    // the database time is read every SYNC_TICKS retry periods
    static final int SYNC_TICKS = 5;
    // a difference between the advance of the wall clock and of nanoTime above this makes the clock read the database time again
    private static final long MAX_DRIFT_MILLIS = 1000L;
    private static final Logger LOG = LoggerFactory.getLogger(DatabaseClock.class);
    // weak keys: the clock does not keep a DataSource of a closed application in memory; the value never references its key
    private static final Map<DataSource, DatabaseClock> CLOCKS = Collections.synchronizedMap(new WeakHashMap<>());
    private final Object lock = new Object();
    // guarded by lock: the database time read last and the nanoTime it belongs to
    private boolean synced;
    private long dbMillisAtSync;
    private long nanosAtSync;
    private long appliedQueryStartNanos;
    // the local wall clock at the last read: if it advanced differently from nanoTime (e.g. a suspend, which stops the
    // monotonic clock on some systems), the database time is read again
    private long wallMillisAtSync;
    // guarded by lock: one caller refreshes at a time
    private boolean refreshRequested;
    private long lastRefreshNanos;


    /**
     * Constructor for DatabaseClock
     */
    private DatabaseClock() {
        synced = false;
        refreshRequested = true;
    }


    /**
     * Get the clock of a data source.
     *
     * @param dataSource the data source
     * @return the clock
     */
    static DatabaseClock of(DataSource dataSource) {
        if (dataSource == null) {
            return new DatabaseClock();
        }
        return CLOCKS.computeIfAbsent(dataSource, k -> new DatabaseClock());
    }


    /**
     * Get the current database time as epoch milliseconds (best effort).
     *
     * @param dao the dao to query the database clock
     * @param retryMillis the retry period of the calling elector in milliseconds
     * @param name the election group name for log messages
     * @return the epoch milliseconds anchored to the database clock
     */
    long now(LeaderElectionDAO dao, long retryMillis, String name) {
        // saturating multiply: guard against an overflow for extreme retry periods
        long refreshThresholdNanos = Long.MAX_VALUE;
        if (retryMillis <= Long.MAX_VALUE / TimeUnit.MILLISECONDS.toNanos(SYNC_TICKS)) {
            refreshThresholdNanos = TimeUnit.MILLISECONDS.toNanos(retryMillis * SYNC_TICKS);
        }

        // the database call runs outside the lock, so a hanging database never blocks other callers of the clock
        boolean needsRefresh;
        boolean everSynced;
        long nowNanos = System.nanoTime();
        synchronized (lock) {
            needsRefresh = refreshRequested || nowNanos - lastRefreshNanos > refreshThresholdNanos || hasDrifted(nowNanos);
            everSynced = synced;
            if (needsRefresh) {
                refreshRequested = false;
                lastRefreshNanos = nowNanos;
            }
        }
        if (needsRefresh) {
            try {
                long queryStartNanos = System.nanoTime();
                long dbNow = dao.getDatabaseCurrentTimeMillis();
                long queryEndNanos = System.nanoTime();
                synchronized (lock) {
                    // a slower query that ends after a newer one must not replace its result
                    if (!synced || queryStartNanos - appliedQueryStartNanos > 0) {
                        dbMillisAtSync = dbNow;
                        nanosAtSync = queryStartNanos + (queryEndNanos - queryStartNanos) / 2;
                        wallMillisAtSync = System.currentTimeMillis() - TimeUnit.NANOSECONDS.toMillis(queryEndNanos - nanosAtSync);
                        appliedQueryStartNanos = queryStartNanos;
                        synced = true;
                    }
                }
            } catch (Exception e) {
                synchronized (lock) {
                    // the next caller tries again
                    refreshRequested = true;
                }
                if (everSynced) {
                    if (LOG.isDebugEnabled()) {
                        LOG.debug("Could not refresh DB clock for [{}], the last DB time keeps advancing: {}", name, e.getMessage());
                    }
                } else {
                    LOG.info("Could not fetch initial DB clock for [{}], using local clock: {}", name, e.getMessage());
                }
            }
        }
        return cachedNow();
    }


    /**
     * Get the current database time from the last database time read, without a database call; the local clock before the
     * first successful read.
     *
     * @return the epoch milliseconds anchored to the database clock
     */
    long cachedNow() {
        synchronized (lock) {
            if (!synced) {
                return System.currentTimeMillis();
            }
            return dbMillisAtSync + TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - nanosAtSync);
        }
    }


    /**
     * Test if the wall clock and the monotonic clock advanced differently since the last read of the database time, e.g.
     * after a suspend of the system or the VM. The caller holds the lock.
     *
     * @param nowNanos the current nanoTime
     * @return true if the clocks drifted apart
     */
    private boolean hasDrifted(long nowNanos) {
        if (!synced) {
            return false;
        }
        long wallAdvance = System.currentTimeMillis() - wallMillisAtSync;
        long monotonicAdvance = TimeUnit.NANOSECONDS.toMillis(nowNanos - nanosAtSync);
        return Math.abs(wallAdvance - monotonicAdvance) > MAX_DRIFT_MILLIS;
    }


    /**
     * Read the database time again on the next call of {@link #now(LeaderElectionDAO, long, String)}.
     */
    void requestRefresh() {
        synchronized (lock) {
            refreshRequested = true;
        }
    }
}
