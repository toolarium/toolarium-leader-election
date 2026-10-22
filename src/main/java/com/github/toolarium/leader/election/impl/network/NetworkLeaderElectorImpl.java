/*
 * NetworkLeaderElectorImpl.java
 *
 * Copyright by toolarium, all rights reserved.
 */
package com.github.toolarium.leader.election.impl.network;

import com.github.toolarium.leader.election.ILeaderElector;
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
 * Implements the {@link ILeaderElector} based on network.
 *
 * @author patrick
 */
public class NetworkLeaderElectorImpl extends AbstractLeaderElectorImpl<LeaderElectionConfiguration> {
    private static final Logger LOG = LoggerFactory.getLogger(NetworkLeaderElectorImpl.class);
    /**
     * Extra milliseconds added on top of {@code retryPeriod} to form the phantom-member stabilisation
     * window.  The window must be strictly greater than {@code retryPeriod} so the scheduler's first
     * tick (fired at {@code t ≈ retryPeriod}) is never considered definitive.  JGroups FD_ALL3 declares
     * failure within ~3-4 s by default, which is always less than {@code retryPeriod + 2 s}.
     */
    private static final long PHANTOM_MEMBER_STABILIZE_EXTRA_MS = 2000L;
    private ScheduledExecutorService scheduledExecuterService;
    private ScheduledFuture<?> scheduledFuture;
    private JChannel channel;
    private volatile long channelInitTime;


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
        this.channelInitTime = 0L;
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

        // Record when the channel was initialised so isElectionResultDefinitive() can suppress
        // premature "not leader" latch release during the JGroups phantom-member window (first 5 s).
        channelInitTime = System.currentTimeMillis();

        // ch.connect() fires viewAccepted() synchronously, but the receiver guard (!isInitialized()) drops that initial event
        // because isInitialized is still false here. Apply the established view directly so a "is leader" result can unblock
        // elect() without waiting for the first scheduler tick.
        View initialView = channel.getView();
        if (initialView != null && !initialView.getMembers().isEmpty()) {
            Address first = initialView.getMembers().get(0);
            if (first.equals(channel.getAddress())) {
                setLeader(true, "" + channel.getAddress());
            }
        }

        scheduledExecuterService = Executors.newScheduledThreadPool(1);
        scheduledFuture = scheduledExecuterService.scheduleAtFixedRate(new NetworkLeaderElectionHandler(channel), 0, getLeaderElectionConfiguration().getRetryPeriod().toMillis(), TimeUnit.MILLISECONDS);
    }


    /**
     * @see com.github.toolarium.leader.election.impl.AbstractLeaderElectorImpl#isElectionResultDefinitive(boolean)
     */
    @Override
    protected boolean isElectionResultDefinitive(boolean isLeaderResult) {
        if (isLeaderResult) {
            return true;
        }
        // Suppress "not leader" latch release during the phantom-member stabilisation window.
        // JGroups FD_ALL3 removes stale members from prior JVM runs within ~3 s; we wait 5 s
        // to be safe before treating a "not leader" view as the definitive election result.
        return System.currentTimeMillis() - channelInitTime
                >= getLeaderElectionConfiguration().getRetryPeriod().toMillis() + PHANTOM_MEMBER_STABILIZE_EXTRA_MS;
    }


    /**
     * @see com.github.toolarium.leader.election.ILeaderElector#close()
     */
    @Override
    public void close() throws LeaderElectionException {
        // Cancel the scheduler and drain any in-flight tick before resetting state.
        // This prevents a concurrent tick from calling setLeader() after super.close()
        // has already reset isLeader to null.
        if (scheduledFuture != null) {
            try {
                scheduledFuture.cancel(false);
            } catch (Exception e) {
                // NOP
            }
            scheduledFuture = null;
        }

        if (scheduledExecuterService != null) {
            try {
                scheduledExecuterService.shutdown();
                long closeMillis = getLeaderElectionConfiguration().getCloseTimeout().toMillis();
                scheduledExecuterService.awaitTermination(closeMillis, TimeUnit.MILLISECONDS);
            } catch (Exception e) {
                // NOP
            }
            scheduledExecuterService = null;
        }

        String channelName = "";
        if (channel != null && channel.getAddress() != null) {
            channelName = "(" + channel.getAddress() + ")";
        }

        super.close();

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
