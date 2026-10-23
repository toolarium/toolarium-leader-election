/*
 * LeaderElectorRecord.java
 *
 * Copyright by toolarium, all rights reserved.
 */
package com.github.toolarium.leader.election.impl.database.dao;

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
    private long timestamp;
    private long forceReleaseDeadline;


    /**
     * Constructor for LeaderElectorRecord
     *
     * @param id the id
     * @param name the name
     * @param instance the instance
     * @param user the user
     * @param timestamp the timestamp as epoch milliseconds
     * @param forceReleaseDeadline the admin force-release deadline as epoch milliseconds (0 = not set)
     */
    public LeaderElectorRecord(String id, String name, String instance, String user, long timestamp, long forceReleaseDeadline) {
        this.id = id;
        this.name = name;
        this.instance = instance;
        this.user = user;
        this.timestamp = timestamp;
        this.forceReleaseDeadline = forceReleaseDeadline;
    }


    /**
     * Constructor for LeaderElectorRecord
     *
     * @param id the id
     * @param name the name
     * @param instance the instance
     * @param user the user
     * @param timestamp the timestamp as epoch milliseconds
     */
    public LeaderElectorRecord(String id, String name, String instance, String user, long timestamp) {
        this(id, name, instance, user, timestamp, 0L);
    }


    /**
     * Constructor for LeaderElectorRecord
     *
     * @param name the name
     * @param instance the instance
     * @param user the user
     * @param timestamp the timestamp as epoch milliseconds
     */
    public LeaderElectorRecord(String name, String instance, String user, long timestamp) {
        this(UUID.randomUUID().toString(), name, instance, user, timestamp, 0L);
    }


    /**
     * Constructor for LeaderElectorRecord
     *
     * @param name the name
     * @param instance the instance
     * @param user the user
     */
    public LeaderElectorRecord(String name, String instance, String user) {
        this(name, instance, user, System.currentTimeMillis());
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
     * Get the timestamp as epoch milliseconds
     *
     * @return the timestamp
     */
    public long getTimestamp() {
        return timestamp;
    }


    /**
     * Get the admin force-release deadline as epoch milliseconds.
     * A value of {@code 0} means no force-release has been requested.
     * When non-zero, the current holder must step down and no new holder may
     * acquire leadership until the deadline has elapsed.
     *
     * @return the force-release deadline epoch millis, or {@code 0} if not set
     */
    public long getForceReleaseDeadline() {
        return forceReleaseDeadline;
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
        return "LeaderElectorRecord [id=" + id + ", name=" + name + ", instance=" + instance + ", user=" + user + ", timestamp=" + timestamp + ", forceReleaseDeadline=" + forceReleaseDeadline + "]";
    }
}
