/*
 * LeaderElectionInformation.java
 *
 * Copyright by toolarium, all rights reserved.
 */
package com.github.toolarium.leader.election.dto;

import java.util.Objects;
import java.util.UUID;


/**
 * Defines the leader election information
 * @author patrick
 */
public class LeaderElectionInformation implements ILeaderElectionInformation {
    private String namespace;
    private String name;
    private String identity;
    private String username;


    /**
     * Constructor for LeaderElectionInformation
     *
     * @param namespace the namespace
     * @param name the name
     * @param identity the identity
     */
    public LeaderElectionInformation(String namespace, String name, String identity) {
        this.namespace = namespace;
        this.name = name;
        if (identity != null && !identity.trim().isEmpty()) {
            this.identity = identity;
        } else {
            this.identity = UUID.randomUUID().toString();
        }
        this.username = System.getProperty("user.name");
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
     * Set the namespace
     *
     * @param namespace the namespace
     * @return this instance for method chaining
     */
    public LeaderElectionInformation setNamespace(String namespace) {
        this.namespace = namespace;
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
     * Set the name
     *
     * @param name the name
     * @return this instance for method chaining
     */
    public LeaderElectionInformation setName(String name) {
        this.name = name;
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
     * Set the identity
     *
     * @param identity the identity
     * @return this instance for method chaining
     */
    public LeaderElectionInformation setIdentity(String identity) {
        this.identity = identity;
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
     *
     * @param username the username to store; must not be {@code null}
     * @return this instance for method chaining
     */
    public LeaderElectionInformation setUsername(String username) {
        if (username != null) {
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
        String clusterName = "";
        if (getNamespace() != null && !getNamespace().trim().isEmpty()) {
            clusterName = getNamespace();
        }

        if (getName() != null && !getName().trim().isEmpty()) {
            if (!clusterName.isEmpty()) {
                clusterName += ".";
            }
            clusterName += getName();
        }

        if (clusterName.isEmpty() && getIdentity() != null && !getIdentity().trim().isEmpty()) {
            clusterName = getIdentity();
        }

        return clusterName;
    }


    /**
     * Get the unique name
     *
     * @return the unique name
     */
    public String getUniqueName() {
        String clusterName = "";
        if (getNamespace() != null && !getNamespace().trim().isEmpty()) {
            clusterName = getNamespace();
        }
        
        if (getName() != null && !getName().trim().isEmpty()) {
            if (!clusterName.isEmpty()) {
                clusterName += ".";
            }
            clusterName += getName();
        }

        if (getIdentity() != null && !getIdentity().trim().isEmpty()) {
            if (!clusterName.isEmpty()) {
                clusterName += ".";
            }
            clusterName += getIdentity();
        }
        return clusterName;
    }    



    /**
     * @see java.lang.Object#toString()
     */
    @Override
    public String toString() {
        return "LeaderElectionInformation [namespace=" + namespace + ", name=" + name + ", identity=" + identity + ", username=" + username + "]";
    }
}
