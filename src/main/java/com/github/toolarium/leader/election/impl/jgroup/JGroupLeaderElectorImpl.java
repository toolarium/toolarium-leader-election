/*
 * JGroupLeaderElectorImpl.java
 *
 * Copyright by toolarium, all rights reserved.
 */
package com.github.toolarium.leader.election.impl.jgroup;

import com.github.toolarium.leader.election.ILeaderElector;
import com.github.toolarium.leader.election.dto.LeaderElectionConfiguration;
import com.github.toolarium.leader.election.dto.LeaderElectionInformation;
import com.github.toolarium.leader.election.impl.AbstractLeaderElectorImpl;
import java.io.IOException;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import org.jgroups.Address;
import org.jgroups.JChannel;
import org.jgroups.View;
import org.jgroups.protocols.pbcast.GMS;
import org.jgroups.stack.Protocol;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Implements the {@link ILeaderElector} based on jgroup.
 * 
 * @author patrick
 */
public class JGroupLeaderElectorImpl extends AbstractLeaderElectorImpl {
    private static final Logger LOG = LoggerFactory.getLogger(JGroupLeaderElectorImpl.class);    

    
    /**
     * Constructor for JGroupLeaderElectorImpl
     *
     * @param leaderElectionInformation the leader election information
     * @param leaderElectionConfiguration the leader election configuration
     * @throws IOException in case of an i/o error
     */
    public JGroupLeaderElectorImpl(LeaderElectionInformation leaderElectionInformation, LeaderElectionConfiguration leaderElectionConfiguration) 
        throws IOException {
        super(leaderElectionInformation, leaderElectionConfiguration);
    }


    /**
     * @see com.github.toolarium.leader.election.impl.AbstractLeaderElectorImpl#init()
     * @throws IOException in case of an i/o error
     */
    protected void init() throws IOException {
        LOG.debug("Initialize jgroup channel...");

        JChannel channel = createChannel();
        if (channel != null) {
            final ScheduledExecutorService scheduledExecuterService = Executors.newScheduledThreadPool(1);
            final ScheduledFuture<?> scheduledFuture;

            scheduledFuture = scheduledExecuterService.scheduleAtFixedRate(
                    new JGroupLeaderElectionHandler(channel), 0, getLeaderElectionConfiguration().getRetryPeriod().getSeconds(), TimeUnit.SECONDS);
            
            Runtime.getRuntime().addShutdownHook(
                    new Thread(JGroupLeaderElectorImpl.class.getName() + ": Shutdown hook") { // add shutdown hook
                        /**
                         * @see java.lang.Thread#run()
                         */
                        @Override
                        public void run() {
                            LOG.info("Exited from cluster [" + getUniqueName() + "] (" + channel.getAddress() + ").");
                            if (scheduledFuture != null) {
                                scheduledFuture.cancel(true);
                            }

                            if (scheduledExecuterService != null) {
                                scheduledExecuterService.shutdown();
                            }
                        }
                    });
        }
    }


    /**
     * Create a channel
     *
     * @return the channel or null
     */
    private JChannel createChannel() {
        JChannel channel = null;
        
        try {
            channel = new JChannel();
            for (Protocol p : channel.getProtocolStack().getProtocols()) {
                if (p instanceof GMS) {
                    ((GMS) p).printLocalAddress(false);
                }
            }
            
            channel.connect(getUniqueName());
            LOG.info("Connected to cluster [" + getUniqueName() + "] (" + channel.getAddress() + ").");
        } catch (Exception e) {
            LOG.warn("Could not join to jgroup cluster [" + getUniqueName() + "]: " + e.getMessage(), e);
        }
        
        return channel;
    }


    /**
     * The jgroup leader election handler
     * 
     * @author patrick
     */
    protected class JGroupLeaderElectionHandler implements Runnable {
        private JChannel channel;

        
        /**
         * Constructor for LeaderElectionHandler
         *
         * @param channel the channel
         */
        public JGroupLeaderElectionHandler(JChannel channel) {
            this.channel = channel;
        }

        
        /**
         * @see java.lang.Runnable#run()
         */
        public void run() {
            try {
                View view = channel.getView();
                Address address = view.getMembers().get(0);
                setLeader(address.equals(channel.getAddress()), "" + channel.getAddress());
            } catch (Exception e) {
                LOG.warn("Error occured while verify jgroup cluster [" + getUniqueName() + "]: " + e.getMessage(), e);
            }
        }
    }
}
