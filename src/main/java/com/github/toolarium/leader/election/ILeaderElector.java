/*
 * ILeaderElector.java
 *
 * Copyright by toolarium, all rights reserved.
 */
package com.github.toolarium.leader.election;

import com.github.toolarium.leader.election.exception.LeaderElectionException;
import java.time.Duration;
import java.time.Instant;


/**
 * Leader elector interface.
 *
 * @author patrick
 */
public interface ILeaderElector extends AutoCloseable {

    /**
     * The unique leader elector identifier
     *
     * @return unique leader elector identifier
     */
    String getId();


    /**
     * Participate in the leader election and block until the first election result is known.
     * After this method returns, {@link #isLeader()} reflects a real election result.
     * The call is idempotent — calling it a second time returns the current leader state immediately.
     *
     * @return {@code true} if this node is the elected leader, {@code false} otherwise
     * @throws LeaderElectionException in case of an initialization or election error
     * @throws InterruptedException if the current thread is interrupted while waiting for the first result
     */
    boolean elect() throws LeaderElectionException, InterruptedException;


    /**
     * Participate in the leader election and block until the first election result is known or
     * the given timeout elapses.
     * After this method returns, {@link #isLeader()} reflects a real election result (if the
     * timeout did not elapse).
     * The call is idempotent — calling it a second time returns the current leader state immediately.
     *
     * @param timeout the maximum time to wait for the first election result; must not be {@code null}
     * @return {@code true} if this node is the elected leader, {@code false} otherwise or on timeout
     * @throws LeaderElectionException in case of an initialization or election error
     * @throws InterruptedException if the current thread is interrupted while waiting
     */
    boolean elect(Duration timeout) throws LeaderElectionException, InterruptedException;


    /**
     * Execute {@code task} on the calling thread if and only if this node is currently the leader.
     * The task receives an {@link ILeaderLease} it can use to detect leadership loss mid-execution.
     * The calling thread is interrupted automatically if leadership is lost while the task runs.
     *
     * @param name a label used for logging; must not be {@code null}
     * @param task the task to run under leadership; must not be {@code null}
     * @return {@code true} if this node was the leader and the task was started, {@code false} if
     *         this node was not the leader (task was not executed)
     * @throws LeaderElectionException if the task throws a checked exception
     */
    boolean runIfLeader(String name, ILeaderTask task) throws LeaderElectionException;


    /**
     * Execute {@code task} on the calling thread if and only if this node is currently the leader.
     * Convenience overload for tasks that do not need the {@link ILeaderLease}.
     *
     * @param name a label used for logging; must not be {@code null}
     * @param task the runnable to execute under leadership; must not be {@code null}
     * @return {@code true} if this node was the leader and the task was started, {@code false} if
     *         this node was not the leader (task was not executed)
     * @throws LeaderElectionException if the task throws an unchecked exception that is wrapped
     */
    default boolean runIfLeader(String name, Runnable task) throws LeaderElectionException {
        return runIfLeader(name, lease -> task.run());
    }


    /**
     * Is leader
     *
     * @return true if the caller is the elected leader otherwise false
     */
    boolean isLeader();


    /**
     * Returns the instant the leadership lease was last confirmed (acquired or successfully extended),
     * or {@code null} if this node is not the leader or has not yet participated in an election.
     *
     * @return the last confirmed leadership instant, or {@code null}
     */
    Instant getLeaderElectorTimestamp();


    /**
     * Close and release all resources held by this leader elector.
     * Implementations may throw {@link LeaderElectionException} if cleanup cannot complete
     * within the configured {@code closeTimeout}.
     *
     * @throws LeaderElectionException if the elector cannot be shut down cleanly in time
     */
    @Override
    void close() throws LeaderElectionException;
}
