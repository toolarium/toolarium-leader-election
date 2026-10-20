/*
 * ILeaderTask.java
 *
 * Copyright by toolarium, all rights reserved.
 */
package com.github.toolarium.leader.election;


/**
 * A task executed under leadership protection. Receives an {@link ILeaderLease} that
 * can be polled ({@link ILeaderLease#isValid()}) or asserted ({@link ILeaderLease#verify()})
 * to detect leadership loss mid-task. The task thread is interrupted when this node loses
 * leadership, so blocking operations will unblock automatically.
 *
 * @author patrick
 */
@FunctionalInterface
public interface ILeaderTask {

    /**
     * Perform the leader-guarded work.
     *
     * @param lease the lease for checking and asserting ongoing leadership
     * @throws Exception if the task fails
     */
    void run(ILeaderLease lease) throws Exception;
}
