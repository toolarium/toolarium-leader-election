/*
 * NetworkLeaderElectorImpl.java
 *
 * Copyright by toolarium, all rights reserved.
 */
package com.github.toolarium.leader.election.impl.network;

import com.github.toolarium.leader.election.LeaderElector;
import com.github.toolarium.leader.election.dto.LeaderElectionConfiguration;
import com.github.toolarium.leader.election.dto.LeaderElectionInformation;
import com.github.toolarium.leader.election.exception.LeaderElectionException;
import com.github.toolarium.leader.election.impl.AbstractLeaderElectorImpl;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import org.jgroups.Address;
import org.jgroups.Global;
import org.jgroups.JChannel;
import org.jgroups.Receiver;
import org.jgroups.View;
import org.jgroups.conf.ConfiguratorFactory;
import org.jgroups.conf.ProtocolStackConfigurator;
import org.jgroups.protocols.UDP;
import org.jgroups.protocols.pbcast.GMS;
import org.jgroups.protocols.pbcast.NAKACK2;
import org.jgroups.stack.Protocol;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


/**
 * Implements the {@link LeaderElector} based on network.
 *
 * @author patrick
 */
public class NetworkLeaderElectorImpl extends AbstractLeaderElectorImpl<LeaderElectionConfiguration> {
    private static final Logger LOG = LoggerFactory.getLogger(NetworkLeaderElectorImpl.class);
    private ScheduledExecutorService scheduledExecuterService;
    private ScheduledFuture<?> scheduledFuture;
    private JChannel channel;


    /**
     * Constructor for NetworkLeaderElectorImpl
     *
     * @param leaderElectionInformation the leader election information
     * @param leaderElectionConfiguration the leader election configuration
     */
    public NetworkLeaderElectorImpl(LeaderElectionInformation leaderElectionInformation, LeaderElectionConfiguration leaderElectionConfiguration) {
        super(leaderElectionInformation, leaderElectionConfiguration);
        this.scheduledExecuterService = null;
        this.scheduledFuture = null;
        this.channel = null;
    }


    /**
     * @see com.github.toolarium.leader.election.impl.AbstractLeaderElectorImpl#initializeImplementation()
     */
    @Override
    protected void initializeImplementation() throws LeaderElectionException {
        if (LOG.isDebugEnabled()) {
            LOG.debug("Initialize network channel...");
        }

        channel = createChannel();
        if (channel == null) {
            throw new LeaderElectionException("Could not join network cluster [" + getLeaderElectionInformation().getUniqueName() + "]. Check previous log warnings for details.");
        }

        // ch.connect() fires viewAccepted() synchronously, but the receiver guard (!isInitialized()) drops that initial event because isInitialized is still
        // false here. Apply the established view directly so leadership is resolved before initialize() returns, without waiting for the first scheduler tick.
        View initialView = channel.getView();
        if (initialView != null && !initialView.getMembers().isEmpty()) {
            Address first = initialView.getMembers().get(0);
            setLeader(first.equals(channel.getAddress()), "" + channel.getAddress());
        }

        scheduledExecuterService = Executors.newScheduledThreadPool(1);
        scheduledFuture = scheduledExecuterService.scheduleAtFixedRate(new NetworkLeaderElectionHandler(channel), 0, getLeaderElectionConfiguration().getRetryPeriod().toMillis(), TimeUnit.MILLISECONDS);
    }


    /**
     * @see com.github.toolarium.leader.election.LeaderElector#close()
     */
    @Override
    public void close() throws LeaderElectionException {
        super.close();

        String channelName = "";
        if (channel != null && channel.getAddress() != null) {
            channelName = "(" + channel.getAddress() + ")";
        }

        if (scheduledFuture != null) {
            try {
                scheduledFuture.cancel(true);
            } catch (Exception e) {
                // NOP
            }
            scheduledFuture = null;
        }

        if (scheduledExecuterService != null) {
            try {
                scheduledExecuterService.shutdown();
            } catch (Exception e) {
                // NOP
            }
            scheduledExecuterService = null;
        }

        if (channel != null) {
            try {
                channel.close();
            } catch (Exception e) {
                // NOP
            }
            channel = null;
            LOG.info("Exited from cluster [{}] {}.", getLeaderElectionInformation().getUniqueName(), channelName);
        }
    }


    /**
     * Create a channel
     *
     * @return the channel or null
     */
    protected JChannel createChannel() {
        JChannel channel = null;

        String uniqueName = getLeaderElectionInformation().getUniqueName();
        try {
            //log_discard_msgs
            ProtocolStackConfigurator protocolStackConfigurator = ConfiguratorFactory.getStackConfigurator(Global.DEFAULT_PROTOCOL_STACK);
            channel = new JChannel(protocolStackConfigurator);
            channel.setDiscardOwnMessages(true);
            for (Protocol p : channel.getProtocolStack().getProtocols()) {
                if (p instanceof GMS) {
                    ((GMS) p).printLocalAddress(false);
                } else if (p instanceof UDP) {
                    ((UDP) p).setLogDiscardMessages(false);
                    ((UDP) p).setLogDiscardMessagesVersion(false);
                } else if (p instanceof NAKACK2) {
                    ((NAKACK2) p).isTrace(false);
                    ((NAKACK2) p).logDiscardMessages(false);
                    //((NAKACK2) p).setSuppressTimeNonMemberWarnings(0);
                }
            }

            final JChannel ch = channel;
            ch.setReceiver(new Receiver() {
                /**
                 * @see org.jgroups.MembershipListener#viewAccepted(org.jgroups.View)
                 */
                @Override
                public void viewAccepted(View newView) {
                    if (!isInitialized() || newView == null || newView.getMembers().isEmpty()) {
                        return;
                    }
                    Address first = newView.getMembers().get(0);
                    setLeader(first.equals(ch.getAddress()), "" + ch.getAddress());
                }
            });
            ch.connect(uniqueName);
            LOG.info("Connected to cluster [{}] ({}).", uniqueName, ch.getAddress());
        } catch (Exception e) {
            if (LOG.isDebugEnabled()) {
                LOG.debug("Could not join to network cluster [{}]: {}", uniqueName, e.getMessage(), e);
            }
            LOG.warn("Could not join to network cluster [{}]: {}", uniqueName, e.getMessage());
        }

        return channel;
    }


    /**
     * The network leader election handler
     *
     * @author patrick
     */
    protected class NetworkLeaderElectionHandler implements Runnable {
        private JChannel channel;


        /**
         * Constructor for NetworkLeaderElectionHandler
         *
         * @param channel the channel
         */
        public NetworkLeaderElectionHandler(JChannel channel) {
            this.channel = channel;
        }


        /**
         * @see java.lang.Runnable#run()
         */
        public void run() {
            try {
                View view = channel.getView();
                if (view == null || view.getMembers().isEmpty()) {
                    return;
                }
                Address address = view.getMembers().get(0);
                setLeader(address.equals(channel.getAddress()), "" + channel.getAddress());
            } catch (Exception e) {
                if (LOG.isDebugEnabled()) {
                    LOG.debug("Error occured while verify network cluster [{}]: {}", getLeaderElectionInformation().getUniqueName(), e.getMessage(), e);
                }
                LOG.warn("Error occured while verify network cluster [{}]: {}", getLeaderElectionInformation().getUniqueName(), e.getMessage());
            }
        }
    }
}
