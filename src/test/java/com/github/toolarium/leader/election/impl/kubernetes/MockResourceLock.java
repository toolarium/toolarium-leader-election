/*
 * MockResourceLock.java
 *
 * Copyright by toolarium, all rights reserved.
 */
package com.github.toolarium.leader.election.impl.kubernetes;

import io.kubernetes.client.extended.leaderelection.LeaderElectionRecord;
import io.kubernetes.client.extended.leaderelection.Lock;
import io.kubernetes.client.openapi.ApiException;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


/**
 * 
 * @author patrick
 */
public class MockResourceLock implements Lock {
    private static final Logger LOG = LoggerFactory.getLogger(MockResourceLock.class);
    private static ReentrantLock lock;
    private static LeaderElectionRecord leaderRecord;

    private int renewCount = 0;
    private int renewCountMax = 4;
    private Consumer<LeaderElectionRecord> onCreate;
    private Consumer<LeaderElectionRecord> onUpdate;
    private Consumer<LeaderElectionRecord> onChange;

    private String idendity;

    
    /**
     * Constructor for MockResourceLock
     *
     * @param idendity the idendity
     */
    public MockResourceLock(String idendity) {
        this.idendity = idendity;
    }

    
    /**
     * Initilaize
     */
    public static synchronized void initialize() {
        
        LOG.debug("INIT");
        MockResourceLock.lock = new ReentrantLock();
        MockResourceLock.leaderRecord = null;
    }

    
    /**
     * Get the current leader record
     *
     * @return the leader election record
     */
    public static synchronized LeaderElectionRecord getLock() {
        return MockResourceLock.leaderRecord;
    }


    /**
     * Set the current leader record
     *
     * @param leaderRecord the leader election record to set
     */
    public static synchronized void setLock(LeaderElectionRecord leaderRecord) {
        
        LOG.debug("INIT");
        MockResourceLock.leaderRecord = leaderRecord;
    }

    
    /**
     * Set the renew count max
     * 
     * @param renewCountMax the renew count max
     */
    public void setRenewCountMax(int renewCountMax) {
        LOG.debug("setRenewCountMax");
        this.renewCountMax = renewCountMax;
    }

   
    /**
     * Set the consumers
     *
     * @param onCreate the on create consumer
     * @param onUpdate the on update consumer
     * @param onChange the on change consumer
     */
    public void setConsumers(Consumer<LeaderElectionRecord> onCreate, Consumer<LeaderElectionRecord> onUpdate, Consumer<LeaderElectionRecord> onChange) {
        this.onCreate = onCreate;
        this.onUpdate = onUpdate;
        this.onChange = onChange;
    }

    
    /**
     * @see io.kubernetes.client.extended.leaderelection.Lock#get()
     */
    @Override
    public LeaderElectionRecord get() throws ApiException {
        lock.lock();
        try {
            LOG.debug("Leader record..." + leaderRecord);
            return leaderRecord;
        } finally {
            lock.unlock();
        }
    }

    
    /**
     * @see io.kubernetes.client.extended.leaderelection.Lock#create(io.kubernetes.client.extended.leaderelection.LeaderElectionRecord)
     */
    @Override
    public boolean create(LeaderElectionRecord record) {
        LOG.debug("[LOCK CREATE] " + KubernetesUtil.getInstance().toString(record));

        lock.lock();
        try {
            if (leaderRecord != null) {
                return false;
            }
            leaderRecord = record;
            
            if (onCreate != null) {
                onCreate.accept(record);
            }
            
            renewCount++;
            return true;
        } finally {
            lock.unlock();
        }
    }

    
    /**
     * @see io.kubernetes.client.extended.leaderelection.Lock#update(io.kubernetes.client.extended.leaderelection.LeaderElectionRecord)
     */
    @Override
    public boolean update(LeaderElectionRecord record) {
        lock.lock();
        try {
            if (renewCount >= renewCountMax) {
                return false;
            }
            LeaderElectionRecord oldRecord = leaderRecord;
            leaderRecord = record;
            
            if (oldRecord != null && oldRecord.getHolderIdentity() != null) {
                LOG.debug("[LOCK UPDATE] " + KubernetesUtil.getInstance().toString(record));
            }
            
            if (onUpdate != null) {
                onUpdate.accept(record);
            }
            
            if (onChange != null && oldRecord != null && oldRecord.getHolderIdentity() != null) {
                if (!oldRecord.getHolderIdentity().equals(record.getHolderIdentity())) {
                    onChange.accept(record);
                }
            }
            
            renewCount++;
            return true;
        } finally {
            lock.unlock();
        }
    }

    
    /**
     * @see io.kubernetes.client.extended.leaderelection.Lock#identity()
     */
    @Override
    public String identity() {
        return this.idendity;
    }

    
    /**
     * @see io.kubernetes.client.extended.leaderelection.Lock#describe()
     */
    @Override
    public String describe() {
        return idendity;
    }
}
