/*
 * DatabaseTestSupport.java
 *
 * Copyright by toolarium, all rights reserved.
 */
package com.github.toolarium.leader.election.impl.database;

import com.github.toolarium.leader.election.ILeaderElector;
import com.github.toolarium.leader.election.LeaderElectionFactory;
import com.github.toolarium.leader.election.dto.LeaderElectionInformation;
import com.github.toolarium.leader.election.dto.LeaderElectionStrategy;
import com.github.toolarium.leader.election.dto.db.DatabaseLeaderElectionConfiguration;
import com.github.toolarium.leader.election.exception.LeaderElectionException;
import com.github.toolarium.leader.election.impl.AbstractLeaderElectorImpl;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import javax.sql.DataSource;


/**
 * Helpers for the DATABASE tests.
 *
 * @author patrick
 */
final class DatabaseTestSupport {

    /**
     * Constructor
     */
    private DatabaseTestSupport() {
        // NOP
    }


    /**
     * Create a DATABASE elector.
     *
     * @param ds the data source
     * @param name the election name
     * @param timeout the timeout
     * @param renewDeadline the renew deadline
     * @param retryPeriod the retry period
     * @return the elector
     * @throws LeaderElectionException in case of an error
     */
    static DatabaseLeaderElectorImpl createElector(DataSource ds, String name, Duration timeout, Duration renewDeadline, Duration retryPeriod) throws LeaderElectionException {
        DatabaseLeaderElectionConfiguration config = new DatabaseLeaderElectionConfiguration(timeout, renewDeadline, retryPeriod);
        config.setCloseTimeout(Duration.ofSeconds(2));
        config.setDataSource(ds);
        ILeaderElector elector = LeaderElectionFactory.getInstance().getLeaderElection(LeaderElectionStrategy.DATABASE, new LeaderElectionInformation(name), config);
        return (DatabaseLeaderElectorImpl) elector;
    }


    /**
     * Wait until a condition is true.
     *
     * @param condition the condition
     * @param timeoutMillis the maximum time to wait
     * @return true if the condition is true
     * @throws InterruptedException if interrupted
     */
    static boolean await(BooleanSupplier condition, long timeoutMillis) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis);
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() > deadline) {
                return false;
            }
            Thread.sleep(20);
        }
        return true;
    }


    /**
     * Gets the stored leader flag, which is not fenced by the renew deadline like {@code isLeader()}.
     *
     * @param elector the elector
     * @return true if the stored flag is set
     * @throws IllegalStateException if the flag cannot be read
     */
    static boolean storedLeaderFlag(ILeaderElector elector) {
        try {
            Field field = AbstractLeaderElectorImpl.class.getDeclaredField("isLeader");
            field.setAccessible(true);
            return Boolean.TRUE.equals(field.get(elector));
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }


    /**
     * Gets the leadership term of an elector.
     *
     * @param elector the elector
     * @return the leadership term
     * @throws IllegalStateException if the term cannot be read
     */
    static long leadershipTerm(ILeaderElector elector) {
        try {
            Method method = AbstractLeaderElectorImpl.class.getDeclaredMethod("getLeadershipTerm");
            method.setAccessible(true);
            return (Long) method.invoke(elector);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }


    /**
     * Close an elector and ignore errors.
     *
     * @param elector the elector or null
     */
    static void closeQuietly(ILeaderElector elector) {
        if (elector == null) {
            return;
        }
        try {
            elector.close();
        } catch (Exception e) {
            // NOP
        }
    }
}
