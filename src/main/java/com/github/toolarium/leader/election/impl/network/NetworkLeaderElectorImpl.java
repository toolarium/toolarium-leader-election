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
import java.util.concurrent.atomic.AtomicBoolean;
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
     * Minimum milliseconds after channel init before a "not leader" result is considered definitive.
     * JGroups FD_ALL3 removes stale members from prior JVM runs within ~3 s; 4 s provides a safe
     * margin for that protocol. The effective stabilization window is
     * {@code max(PHANTOM_MEMBER_STABILIZE_MS, retryPeriod + 1000)} so that at least one full
     * scheduler tick can observe a clean (phantom-free) view before the "not leader" result is
     * released to the election latch.
     */
    private static final long PHANTOM_MEMBER_STABILIZE_MS = 4000L;
    // volatile: written by initializeImplementation() (under lifecycleLock writeLock) and read by
    // close() without holding any lock; volatile is required for the JMM happens-before guarantee.
    private volatile ScheduledExecutorService scheduledExecuterService;
    private volatile ScheduledFuture<?> scheduledFuture;
    // volatile: written by initializeImplementation() (thread A, under lifecycleLock writeLock) and
    // read by close() (thread B, without any lock); without volatile close() may read a stale null
    // and skip channel.close(), leaking the JGroups socket and cluster membership (TS-1).
    private volatile JChannel channel;
    private volatile long channelInitTime;
    private final AtomicBoolean closing = new AtomicBoolean(false);


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
        closing.set(false);

        if (LOG.isDebugEnabled()) {
            LOG.debug("Initialize network channel...");
        }

        channel = createChannel();
        if (channel == null) {
            throw new LeaderElectionException("Could not join network cluster [" + getLeaderElectionInformation().getElectionGroupName() + "]. Check previous log warnings for details.");
        }

        // Record when the channel was initialised so isElectionResultDefinitive() can suppress
        // premature "not leader" latch release during the JGroups phantom-member window (first 5 s).
        channelInitTime = System.nanoTime();

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

        String groupName = getLeaderElectionInformation().getElectionGroupName();
        scheduledExecuterService = Executors.newScheduledThreadPool(1, runnable -> {
            Thread t = new Thread(runnable, "leader-election-network-" + groupName);
            t.setDaemon(true);
            return t;
        });
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
        // JGroups FD_ALL3 removes stale members from prior JVM runs within ~3 s. The effective
        // window is max(4 s, retryPeriod + 1 s) so that at least one full scheduler tick can
        // observe a clean (phantom-free) view before the result is released.
        long retryMs = getLeaderElectionConfiguration().getRetryPeriod().toMillis();
        long windowMs = Math.max(PHANTOM_MEMBER_STABILIZE_MS, retryMs + 1000L);
        return System.nanoTime() - channelInitTime >= TimeUnit.MILLISECONDS.toNanos(windowMs);
    }


    /**
     * @see com.github.toolarium.leader.election.ILeaderElector#close()
     */
    @Override
    public void close() throws LeaderElectionException {
        if (!closing.compareAndSet(false, true)) {
            return;
        }

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
            LOG.info("Exited from cluster [{}] {}.", getLeaderElectionInformation().getElectionGroupName(), channelName);
        }
    }


    /**
     * Create a channel
     *
     * @return the channel or null
     */
    protected JChannel createChannel() {
        JChannel channel = null;

        String clusterName = getLeaderElectionInformation().getElectionGroupName();
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
            ch.connect(clusterName);
            LOG.info("Connected to cluster [{}] ({}).", clusterName, ch.getAddress());
        } catch (Exception e) {
            // Close the partially-constructed channel so it does not leak the JGroups
            // socket. Return null so initializeImplementation() fails fast.
            if (channel != null) {
                try {
                    channel.close();
                } catch (Exception ignored) {
                    // best-effort cleanup
                }
                channel = null;
            }
            if (LOG.isDebugEnabled()) {
                LOG.debug("Could not join to network cluster [{}]: {}", clusterName, e.getMessage(), e);
            }
            LOG.info("Could not join to network cluster [{}]: {}, will retry on next election tick.", clusterName, e.getMessage());
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
                    // No view available; after the stabilisation window, unblock elect() so
                    // the caller does not hang indefinitely when UDP cannot deliver a view.
                    if (!hasElectionResult() && isElectionResultDefinitive(false)) {
                        setLeader(false, null);
                    }
                    return;
                }
                Address address = view.getMembers().get(0);
                setLeader(address.equals(channel.getAddress()), "" + channel.getAddress());
            } catch (Exception e) {
                if (LOG.isDebugEnabled()) {
                    LOG.debug("Error occurred while verifying network cluster [{}]: {}", getLeaderElectionInformation().getUniqueName(), e.getMessage(), e);
                }
                LOG.info("Error occurred while verifying network cluster [{}]: {}, will retry on next election tick.", getLeaderElectionInformation().getUniqueName(), e.getMessage());
                // Step down when the cluster state is unknown so a broken channel
                // does not keep this node as leader indefinitely.
                if (isLeader()) {
                    setLeader(false, null);
                }
                // Ensure elect() can return even when the very first tick fails.
                if (!hasElectionResult() && isElectionResultDefinitive(false)) {
                    setLeader(false, null);
                }
            }
        }
    }
}
