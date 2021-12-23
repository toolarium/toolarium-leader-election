/*
 * LeaderElectionInformation.java
 *
 * Copyright by toolarium, all rights reserved.
 */
package com.github.toolarium.leader.election.dto;

import java.util.Objects;


/**
 * Defines the leader election information
 * @author patrick
 */
public class LeaderElectionInformation {
    private String namespace;
    private String name;
    private String identity;
    

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
        this.identity = identity;
    }

    
    /**
     * Constructor for LeaderElectionInformation
     *
     * @param identity the identity
     */
    public LeaderElectionInformation(String identity) {
        this("", "", identity);
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
     */
    public void setNamespace(String namespace) {
        this.namespace = namespace;
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
     */
    public void setName(String name) {
        this.name = name;
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
     */
    public void setIdentity(String identity) {
        this.identity = identity;
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
     * @see java.lang.Object#toString()
     */
    @Override
    public String toString() {
        return "LeaderElectionInformation [namespace=" + namespace + ", name=" + name + ", identity=" + identity + "]";
    }
}
