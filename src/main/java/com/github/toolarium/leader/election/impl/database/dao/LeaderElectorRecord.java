/*
 * LeaderElectorRecord.java
 *
 * Copyright by toolarium, all rights reserved.
 */
package com.github.toolarium.leader.election.impl.database.dao;

import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.Objects;
import java.util.UUID;

/**
 * The leader elector record
 * 
 * @author patrick
 */
public class LeaderElectorRecord {
    private String id;
    private String name;
    private String instance;
    private String user;
    private String timestamp;
    
    
    /**
     * Constructor for LeaderElectorRecord
     *
     * @param id the id
     * @param name the name
     * @param instance the instance
     * @param user the user
     * @param timestamp the timestamp
     */
    public LeaderElectorRecord(String id, String name, String instance, String user, String timestamp) {
        this.id = id;
        this.name = name;
        this.instance = instance;
        this.user = user;
        this.timestamp = timestamp;
    }

    
    /**
     * Constructor for LeaderElectorRecord
     *
     * @param name the name
     * @param instance the instance
     * @param user the user
     */
    public LeaderElectorRecord(String name, String instance, String user) {
        this.id = UUID.randomUUID().toString();
        this.name = name;
        this.instance = instance;
        this.user = user;
        this.timestamp = DateTimeFormatter.ISO_INSTANT.format(Instant.now()).substring(0, 24) + "Z";
    }

    
    /**
     * Get the id
     *
     * @return the id
     */
    public String getId() {
        return id;
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
     * Get the instance
     *
     * @return the instance
     */
    public String getInstance() {
        return instance;
    }
    
    
    /**
     * Get the user
     *
     * @return the user
     */
    public String getUser() {
        return user;
    }
    
    
    /**
     * Get the timestamp
     *
     * @return the timestamp
     */
    public String getTimestamp() {
        return timestamp;
    }


    /**
     * @see java.lang.Object#hashCode()
     */
    @Override
    public int hashCode() {
        return Objects.hash(id, name);
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
        
        LeaderElectorRecord other = (LeaderElectorRecord) obj;
        return Objects.equals(id, other.id) && Objects.equals(name, other.name);
    }


    /**
     * @see java.lang.Object#toString()
     */
    @Override
    public String toString() {
        return "LeaderElectorRecord [id=" + id + ", name=" + name + ", instance=" + instance + ", user=" + user + ", timestamp=" + timestamp + "]";
    }
}
