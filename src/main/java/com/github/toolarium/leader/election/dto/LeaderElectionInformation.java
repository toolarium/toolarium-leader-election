/*
 * LeaderElectionInformation.java
 *
 * Copyright by toolarium, all rights reserved.
 */
package com.github.toolarium.leader.election.dto;

import com.github.toolarium.leader.election.util.IdentityUtil;
import java.util.Objects;


/**
 * Defines the leader election information.
 *
 * <p>String fields are subject to maximum length constraints that match the database column sizes
 * used by the DATABASE strategy:
 * <ul>
 *   <li>{@code namespace} and {@code name}: the combined election group name
 *       ({@code "namespace.name"}) must not exceed {@value #MAX_ELECTION_GROUP_NAME_LENGTH} characters
 *       (maps to the {@code name VARCHAR(512)} column).</li>
 *   <li>{@code identity}: at most {@value #MAX_IDENTITY_LENGTH} characters
 *       (maps to the {@code instance VARCHAR(255)} column).</li>
 *   <li>{@code username}: at most {@value #MAX_USERNAME_LENGTH} characters
 *       (maps to the {@code username VARCHAR(255)} column).</li>
 * </ul>
 *
 * @author patrick
 */
public class LeaderElectionInformation implements ILeaderElectionInformation {
    /**
     * Maximum length of the election group name ({@code namespace.name}),
     * matching the database {@code name} column.
     */
    public static final int MAX_ELECTION_GROUP_NAME_LENGTH = 512;

    /**
     * Maximum length of the identity field,
     * matching the database {@code instance} column.
     */
    public static final int MAX_IDENTITY_LENGTH = 255;

    /**
     * Maximum length of the username field,
     * matching the database {@code username} column.
     */
    public static final int MAX_USERNAME_LENGTH = 255;

    private String namespace;
    private String name;
    private String identity;
    private String username;
    // cache of getElectionGroupName(), cleared when a part of the name changes
    private volatile String electionGroupName;


    /**
     * Constructor for LeaderElectionInformation
     *
     * @param namespace the namespace; may be {@code null}. Together with {@code name} the combined
     *                  election group name must not exceed {@value #MAX_ELECTION_GROUP_NAME_LENGTH} characters.
     * @param name the name. Together with {@code namespace} the combined election group name must not
     *             exceed {@value #MAX_ELECTION_GROUP_NAME_LENGTH} characters.
     * @param identity the identity; at most {@value #MAX_IDENTITY_LENGTH} characters.
     *                 When {@code null} or blank the identity is resolved automatically:
     *                 in Kubernetes the {@code HOSTNAME} environment variable (the pod name),
     *                 otherwise the local host name, or a random UUID as last resort.
     * @throws IllegalArgumentException if any field exceeds its maximum length
     */
    public LeaderElectionInformation(String namespace, String name, String identity) {
        this.namespace = namespace;
        this.name = name;
        this.electionGroupName = null;
        if (identity != null && !identity.isBlank()) {
            validateMaxLength("identity", identity, MAX_IDENTITY_LENGTH);
            this.identity = identity;
        } else {
            this.identity = IdentityUtil.getInstance().resolveIdentity();
        }
        this.username = System.getProperty("user.name");
        validateElectionGroupNameLength();
    }


    /**
     * Constructor for LeaderElectionInformation
     *
     * @param name the election group name
     */
    public LeaderElectionInformation(String name) {
        this(null, name, null);
    }
    
    
    /**
     * Get the namespace
     *
     * @return the namespace
     */
    public String getNamespace() {
        return namespace;
    }
    
    
    /**
     * Set the namespace. Together with {@code name} the combined election group name
     * ({@code "namespace.name"}) must not exceed {@value #MAX_ELECTION_GROUP_NAME_LENGTH} characters.
     *
     * @param namespace the namespace
     * @return this instance for method chaining
     * @throws IllegalArgumentException if the resulting election group name exceeds its maximum length
     */
    public LeaderElectionInformation setNamespace(String namespace) {
        String previousNamespace = this.namespace;
        this.namespace = namespace;
        this.electionGroupName = null;
        try {
            validateElectionGroupNameLength();
        } catch (IllegalArgumentException e) {
            this.namespace = previousNamespace;
            this.electionGroupName = null;
            throw e;
        }
        return this;
    }
    
    
    /**
     * Get the name
     *
     * @return the name
     */
    public String getName() {
        return name;
    }
    
    
    /**
     * Set the name. Together with {@code namespace} the combined election group name
     * ({@code "namespace.name"}) must not exceed {@value #MAX_ELECTION_GROUP_NAME_LENGTH} characters.
     *
     * @param name the name
     * @return this instance for method chaining
     * @throws IllegalArgumentException if the resulting election group name exceeds its maximum length
     */
    public LeaderElectionInformation setName(String name) {
        String previousName = this.name;
        this.name = name;
        this.electionGroupName = null;
        try {
            validateElectionGroupNameLength();
        } catch (IllegalArgumentException e) {
            this.name = previousName;
            this.electionGroupName = null;
            throw e;
        }
        return this;
    }
    
    
    /**
     * Get the identity
     *
     * @return the identity
     */
    public String getIdentity() {
        return identity;
    }
    
    
    /**
     * Set the identity. Must not exceed {@value #MAX_IDENTITY_LENGTH} characters.
     *
     * @param identity the identity
     * @return this instance for method chaining
     * @throws IllegalArgumentException if the identity exceeds {@value #MAX_IDENTITY_LENGTH} characters
     */
    public LeaderElectionInformation setIdentity(String identity) {
        if (identity != null) {
            validateMaxLength("identity", identity, MAX_IDENTITY_LENGTH);
        }
        this.identity = identity;
        this.electionGroupName = null;
        return this;
    }


    /**
     * @see com.github.toolarium.leader.election.dto.ILeaderElectionInformation#getUsername()
     */
    @Override
    public String getUsername() {
        return username;
    }


    /**
     * Set the username. Use this to override the default OS username
     * ({@code System.getProperty("user.name")}) with an application-supplied value.
     * Must not exceed {@value #MAX_USERNAME_LENGTH} characters.
     *
     * @param username the username to store; must not be {@code null}
     * @return this instance for method chaining
     * @throws IllegalArgumentException if the username exceeds {@value #MAX_USERNAME_LENGTH} characters
     */
    public LeaderElectionInformation setUsername(String username) {
        if (username != null) {
            validateMaxLength("username", username, MAX_USERNAME_LENGTH);
            this.username = username;
        }
        return this;
    }


    /**
     * @see java.lang.Object#hashCode()
     */
    @Override
    public int hashCode() {
        return Objects.hash(identity, name, namespace);
    }


    /**
     * @see java.lang.Object#equals(java.lang.Object)
     */
    @Override
    public boolean equals(Object obj) {
        if (this == obj) {
            return true;
        }

        if (obj == null) {
            return false;
        }

        if (getClass() != obj.getClass()) {
            return false;
        }

        LeaderElectionInformation other = (LeaderElectionInformation) obj;
        return Objects.equals(identity, other.identity) && Objects.equals(name, other.name) && Objects.equals(namespace, other.namespace);
    }

    
    /**
     * Get the election group name for the DATABASE strategy.
     * Returns {@code namespace.name} (without identity), so that all nodes competing for the same
     * resource share one database row regardless of their per-node identity value.
     * Falls back to {@code identity} when both namespace and name are blank, preserving the
     * single-argument constructor behaviour.
     *
     * @return the election group name
     */
    public String getElectionGroupName() {
        String cached = electionGroupName;
        if (cached != null) {
            return cached;
        }
        String clusterName = "";
        if (getNamespace() != null && !getNamespace().isBlank()) {
            clusterName = getNamespace();
        }

        if (getName() != null && !getName().isBlank()) {
            if (!clusterName.isEmpty()) {
                clusterName += ".";
            }
            clusterName += getName();
        }

        if (clusterName.isEmpty() && getIdentity() != null && !getIdentity().isBlank()) {
            clusterName = getIdentity();
        }

        electionGroupName = clusterName;
        return clusterName;
    }


    /**
     * Get the unique name
     *
     * @return the unique name
     */
    public String getUniqueName() {
        String clusterName = "";
        if (getNamespace() != null && !getNamespace().isBlank()) {
            clusterName = getNamespace();
        }
        
        if (getName() != null && !getName().isBlank()) {
            if (!clusterName.isEmpty()) {
                clusterName += ".";
            }
            clusterName += getName();
        }

        if (getIdentity() != null && !getIdentity().isBlank()) {
            if (!clusterName.isEmpty()) {
                clusterName += ".";
            }
            clusterName += getIdentity();
        }
        return clusterName;
    }    


    /**
     * Validate that the combined election group name does not exceed the database column limit.
     *
     * @throws IllegalArgumentException if the election group name exceeds {@value #MAX_ELECTION_GROUP_NAME_LENGTH} characters
     */
    private void validateElectionGroupNameLength() {
        String groupName = getElectionGroupName();
        if (groupName.length() > MAX_ELECTION_GROUP_NAME_LENGTH) {
            throw new IllegalArgumentException(
                    "The combined election group name (namespace.name) must not exceed "
                    + MAX_ELECTION_GROUP_NAME_LENGTH + " characters, got " + groupName.length() + ".");
        }
    }


    /**
     * @see java.lang.Object#toString()
     */
    @Override
    public String toString() {
        return "LeaderElectionInformation [namespace=" + namespace + ", name=" + name + ", identity=" + identity + ", username=" + username + "]";
    }


    /**
     * Validate that a single field does not exceed the given maximum length.
     *
     * @param fieldName the field name (for the error message)
     * @param value the value to check
     * @param maxLength the maximum allowed length
     * @throws IllegalArgumentException if the value exceeds the maximum length
     */
    private static void validateMaxLength(String fieldName, String value, int maxLength) {
        if (value != null && value.length() > maxLength) {
            throw new IllegalArgumentException(fieldName + " must not exceed " + maxLength + " characters, got " + value.length() + ".");
        }
    }
}
