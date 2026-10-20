/*
 * ILeaderElectionInformation.java
 *
 * Copyright by toolarium, all rights reserved.
 */
package com.github.toolarium.leader.election.dto;


/**
 * Read-only view of the leader election identification (namespace, name, identity, username).
 *
 * @author patrick
 */
public interface ILeaderElectionInformation {

    /**
     * Get the namespace
     *
     * @return the namespace
     */
    String getNamespace();


    /**
     * Get the name
     *
     * @return the name
     */
    String getName();


    /**
     * Get the identity
     *
     * @return the identity
     */
    String getIdentity();


    /**
     * Get the username of the OS user running this process.
     * Defaults to {@code System.getProperty("user.name")} and can be overridden via
     * {@link LeaderElectionInformation#setUsername(String)}.
     *
     * @return the username; never {@code null}
     */
    String getUsername();


    /**
     * Returns the election group key used as the shared database / lock row identifier.
     * All nodes competing for the same resource must produce the same value.
     *
     * @return the election group name
     */
    String getElectionGroupName();


    /**
     * Returns the fully-qualified unique name combining namespace, name, and identity.
     *
     * @return the unique name
     */
    String getUniqueName();
}
