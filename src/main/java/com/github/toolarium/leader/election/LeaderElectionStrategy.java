/*
 * LeaderElectionStrategy.java
 *
 * Copyright by toolarium, all rights reserved.
 */
package com.github.toolarium.leader.election;

/**
 * Defines the leader election strategy.
 *
 * @author patrick
 */
public enum LeaderElectionStrategy {
    /** OS-level file lock on a shared filesystem path. */
    FILE,
    /** JDBC-based optimistic locking against a shared database table. */
    DATABASE,
    /** Network-based cluster membership leader election (jgroup). */
    NETWORK,
    /** Kubernetes lease-based leader election. */
    KUBERNETES;
}
