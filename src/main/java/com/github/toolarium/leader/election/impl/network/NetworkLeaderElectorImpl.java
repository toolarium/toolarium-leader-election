/*
 * NetworkLeaderElectorImpl.java
 *
 * Copyright by toolarium, all rights reserved.
 */
package com.github.toolarium.leader.election.impl.network;

import com.github.toolarium.leader.election.ILeaderElector;
import com.github.toolarium.leader.election.dto.LeaderElectionInformation;
import com.github.toolarium.leader.election.dto.network.NetworkLeaderElectionConfiguration;
import com.github.toolarium.leader.election.exception.LeaderElectionException;
import com.github.toolarium.leader.election.impl.AbstractLeaderElectorImpl;
import com.github.toolarium.leader.election.impl.LeaderElectionExecutors;
import java.io.IOException;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.ByteBuffer;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import org.jgroups.Address;
import org.jgroups.BytesMessage;
import org.jgroups.Global;
import org.jgroups.JChannel;
import org.jgroups.Message;
import org.jgroups.Receiver;
import org.jgroups.View;
import org.jgroups.ViewId;
import org.jgroups.conf.ConfiguratorFactory;
import org.jgroups.conf.ProtocolStackConfigurator;
import org.jgroups.protocols.Encrypt;
import org.jgroups.protocols.FD_SOCK2;
import org.jgroups.protocols.SERIALIZE;
import org.jgroups.protocols.SYM_ENCRYPT;
import org.jgroups.protocols.TP;
import org.jgroups.protocols.UDP;
import org.jgroups.protocols.pbcast.GMS;
import org.jgroups.protocols.pbcast.NAKACK2;
import org.jgroups.stack.Protocol;
import org.jgroups.stack.ProtocolStack;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


/**
 * Implements the {@link ILeaderElector} based on network.
 *
 * <p>The coordinator (first member) of the JGroups cluster view is the leader. Two safeguards
 * limit the time two nodes can both consider themselves leader:
 * <ul>
 *   <li>Quorum: with {@link NetworkLeaderElectionConfiguration#getMinimumMembers()} set to a
 *       majority of the cluster, the coordinator of a minority partition does not take the lead.</li>
 *   <li>Confirmation by the members: once per retry period the coordinator sends a random challenge to the
 *       cluster, and every member that sees it as the coordinator of its own view answers it. The coordinator
 *       only confirms its lead when at least one other member, or {@code minimumMembers - 1} members when a
 *       quorum is set, answered a challenge it sent within the renew deadline; a node alone in its view
 *       confirms itself. A coordinator whose view is stale, e.g. after a long JVM pause while the others
 *       elected a new coordinator, gets no answers, and answers to older challenges (buffered or replayed) do
 *       not count.</li>
 *   <li>Time-based fencing: {@link #isLeader()} returns {@code false} when the lead was not
 *       confirmed within the renew deadline.</li>
 * </ul>
 * Without a quorum each side of a network partition elects its own leader until the partitions merge.
 *
 * <p>With an encryption key all cluster messages are encrypted directly above the transport, including the headers of the
 * protocols and the messages of discovery and failure detection ({@code SERIALIZE} above {@code SYM_ENCRYPT}, CBC mode with a
 * random IV per message), so a node without the key can neither read, forge nor join. Readable remain the cluster name, the
 * outer sender and receiver addresses, the type of membership messages (join, leave, view, merge; {@code SERIALIZE} keeps the
 * {@code GMS} header outside), and the TCP connections of {@code FD_SOCK2}, which detect crashed members outside the protocol
 * stack. The encryption protocols log every packet they reject; their log level is set in the logging configuration of the
 * application (the levels of JGroups protocols cannot be changed through SLF4J). JGroups still reads every incoming packet before it
 * is decrypted (including Java deserialization of object messages), so the cluster must run in a trusted network segment.
 * Without a key and without a custom JGroups configuration an info message is logged.
 *
 * @author patrick
 */
public class NetworkLeaderElectorImpl extends AbstractLeaderElectorImpl<NetworkLeaderElectionConfiguration> {
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
    // the warning about an unprotected cluster is logged once per JVM
    private static final AtomicBoolean UNPROTECTED_WARNING_LOGGED = new AtomicBoolean(false);
    // FD_SOCK2 listens on the transport port + offset (100) and tries only a few ports; the other members compute the same ports
    // to connect. Windows (Hyper-V, WinNAT) reserves blocks of about 100 ports, so with an operating-system chosen transport port
    // the failure detection ports are often reserved and joining fails. A transport port is therefore chosen whose failure
    // detection port is free.
    private static final int PORT_SELECTION_ATTEMPTS = 50;
    private static final int MAX_PORT = 65535;
    private static final int MIN_DYNAMIC_PORT = 49152;
    // the challenge the coordinator sends to the cluster, and the heartbeat a member answers it with; each is the marker
    // followed by the random number of the challenge
    private static final byte[] CHALLENGE = new byte[] {'C', 'H'};
    private static final byte[] HEARTBEAT = new byte[] {'H', 'B'};
    private static final int MESSAGE_LENGTH = HEARTBEAT.length + Long.BYTES;
    private static final SecureRandom CHALLENGE_RANDOM = new SecureRandom();
    // see getConfirmationNanos()
    private static final long NOT_CONFIRMED = Long.MIN_VALUE;
    // The periodic view checks on the threads shared by all electors (LeaderElectionExecutors).
    // volatile: written by initializeImplementation() (under lifecycleLock writeLock) and read by
    // close() without holding any lock; volatile is required for the JMM happens-before guarantee.
    private volatile LeaderElectionExecutors.PeriodicTask viewCheckTask;
    // volatile: written by initializeImplementation() (thread A, under lifecycleLock writeLock) and
    // read by close() (thread B, without any lock); without volatile close() may read a stale null
    // and skip channel.close(), leaking the JGroups socket and cluster membership (TS-1).
    private volatile JChannel channel;
    private volatile long channelInitTime;
    // nanoTime of the last cluster view that confirmed the lead; read by isLeader() for time-based fencing
    private volatile long lastConfirmedNanos;
    // true while the coordinator role is refused because the view is below the quorum (logged once per change)
    private volatile boolean quorumMissing;
    // nanoTime when the quorum was found missing, and whether the missing quorum was already warned about (once per outage)
    private volatile long quorumMissingSinceNanos;
    private volatile boolean quorumWarned;
    // the challenges this node sent as coordinator within the renew deadline: random number -> nanoTime it was sent
    private final Map<Long, Long> challenges = new ConcurrentHashMap<>();
    // per member that sees this node as coordinator: nanoTime of the newest challenge it answered
    private final Map<Address, Long> heartbeats = new ConcurrentHashMap<>();
    // views are applied by the JGroups thread and by the periodic check: a view older than the last applied one is ignored
    private final Object viewLock = new Object();
    private ViewId lastViewId;
    private final AtomicBoolean closing = new AtomicBoolean(false);
    // the local address and its text, so that the text is not built again on every check
    private volatile Address namedAddress;
    private volatile String addressName;


    /**
     * Constructor for NetworkLeaderElectorImpl
     *
     * @param leaderElectionInformation the leader election information
     * @param leaderElectionConfiguration the leader election configuration
     */
    public NetworkLeaderElectorImpl(LeaderElectionInformation leaderElectionInformation, NetworkLeaderElectionConfiguration leaderElectionConfiguration) {
        super(leaderElectionInformation, leaderElectionConfiguration);
        this.viewCheckTask = null;
        this.channel = null;
        this.channelInitTime = 0L;
        this.lastConfirmedNanos = 0L;
        this.quorumMissing = false;
    }


    /**
     * @see com.github.toolarium.leader.election.impl.AbstractLeaderElectorImpl#initializeImplementation()
     */
    @Override
    protected void initializeImplementation() throws LeaderElectionException {
        closing.set(false);
        quorumMissing = false;
        quorumWarned = false;
        heartbeats.clear();
        challenges.clear();
        synchronized (viewLock) {
            lastViewId = null;
        }

        if (LOG.isDebugEnabled()) {
            LOG.debug("Initialize network channel...");
        }

        channel = createChannel();
        if (channel == null) {
            throw new LeaderElectionException("Could not join network cluster [" + getLeaderElectionInformation().getElectionGroupName() + "], see the previous log message for the cause.");
        }

        // Record when the channel was initialised so isElectionResultDefinitive() can suppress
        // premature "not leader" latch release during the JGroups phantom-member window (first 5 s).
        channelInitTime = System.nanoTime();

        // ch.connect() fires viewAccepted() synchronously, but the receiver guard (!isInitialized()) drops that initial event
        // because isInitialized is still false here. Apply the established view directly so a "is leader" result can unblock
        // elect() without waiting for the first scheduler tick.
        View initialView = channel.getView();
        if (initialView != null && !initialView.getMembers().isEmpty()) {
            if (isLeaderOfView(initialView, channel.getAddress())) {
                applyView(channel, initialView);
                // let the members confirm the lead without waiting for the first periodic check
                sendChallenge(channel, initialView, channel.getAddress());
            }
        }

        String groupName = getLeaderElectionInformation().getElectionGroupName();
        viewCheckTask = LeaderElectionExecutors.getInstance().schedulePeriodic("leader-election-network-" + groupName, new NetworkLeaderElectionHandler(channel),
                                                                               getLeaderElectionConfiguration().getRetryPeriod().toMillis());
    }


    /**
     * @see com.github.toolarium.leader.election.ILeaderElector#isLeader()
     *     Overrides the cached boolean with a monotonic-clock expiry check: the lead is only valid while it
     *     was confirmed against a cluster view within the renew deadline.
     */
    @Override
    public boolean isLeader() {
        if (!super.isLeader()) {
            return false;
        }
        return System.nanoTime() - lastConfirmedNanos <= getLeaderElectionConfiguration().getRenewDeadline().toNanos();
    }


    /**
     * @see com.github.toolarium.leader.election.impl.AbstractLeaderElectorImpl#getLeadConfirmedNanos()
     */
    @Override
    protected long getLeadConfirmedNanos() {
        long nanos = lastConfirmedNanos;
        if (nanos == 0L) {
            return super.getLeadConfirmedNanos();
        }
        return nanos;
    }


    /**
     * Check if the given address is the leader of the view: it must be the coordinator and the view
     * must contain at least the configured minimum number of members.
     *
     * @param view the cluster view, not empty
     * @param self the local address
     * @return true if the local node is the leader of the view
     */
    protected boolean isLeaderOfView(View view, Address self) {
        if (!view.getMembers().get(0).equals(self)) {
            return false;
        }

        int minimumMembers = getLeaderElectionConfiguration().getMinimumMembers();
        boolean hasQuorum = view.size() >= minimumMembers;
        String groupName = getLeaderElectionInformation().getElectionGroupName();
        if (hasQuorum == quorumMissing) {
            quorumMissing = !hasQuorum;
            if (hasQuorum) {
                LOG.info("Cluster [{}] reached the quorum of {} members ({} members).", groupName, minimumMembers, view.size());
            } else {
                // normal during a startup or a rolling restart: only a quorum that stays missing is a warning
                quorumMissingSinceNanos = System.nanoTime();
                quorumWarned = false;
                LOG.info("Cluster [{}] has {} of {} required members; this node does not take the lead.", groupName, view.size(), minimumMembers);
            }
        } else if (!hasQuorum && !quorumWarned
                   && System.nanoTime() - quorumMissingSinceNanos > getLeaderElectionConfiguration().getTimeout().toNanos()) {
            quorumWarned = true;
            LOG.warn("Cluster [{}] has had only {} of {} required members for more than {} s, so it has no leader. Check that enough nodes "
                     + "are running and can reach each other, or lower minimumMembers.", groupName, view.size(), minimumMembers,
                     getLeaderElectionConfiguration().getTimeout().getSeconds());
        }
        return hasQuorum;
    }


    /**
     * Apply a cluster view of a channel, unless the channel is not the current one anymore: a callback or a check of a
     * closed channel that is late must not apply its view to a later session.
     *
     * @param ch the channel the view belongs to
     * @param view the cluster view, not empty
     * @return true if the view was applied
     */
    protected boolean applyView(JChannel ch, View view) {
        synchronized (viewLock) {
            // checked under the view lock: the initialization resets the view state under it before the new channel is used
            if (ch == null || ch != channel) {
                return false;
            }
            applyView(view, ch.getAddress());
            return true;
        }
    }


    /**
     * Apply a cluster view: update the leader state and, when this node leads, the confirmation time.
     *
     * @param view the cluster view, not empty
     * @param self the local address
     */
    protected void applyView(View view, Address self) {
        synchronized (viewLock) {
            ViewId viewId = view.getViewId();
            if (lastViewId != null && viewId != null && viewId.compareToIDs(lastViewId) < 0) {
                // an older view read by the periodic check must not overwrite a newer one applied meanwhile
                return;
            }
            lastViewId = viewId;
            // a set of the members: retainAll() on the member list would compare every key with every member
            Set<Address> members = new HashSet<>(view.getMembers());
            heartbeats.keySet().retainAll(members);

            if (isLeaderOfView(view, self)) {
                long confirmedNanos = getConfirmationNanos(view, self);
                if (confirmedNanos != NOT_CONFIRMED) {
                    // The lead counts from the send time of the challenge the members answered, not from now: otherwise it could
                    // last up to twice the renew deadline after the members last confirmed it. Set before setLeader so isLeader()
                    // never sees the new lead with a stale confirmation time.
                    if (confirmedNanos - lastConfirmedNanos > 0 || !super.isLeader()) {
                        lastConfirmedNanos = confirmedNanos;
                    }
                    setLeader(true, getAddressName(self));
                } else if (super.isLeader() && System.nanoTime() - lastConfirmedNanos > getLeaderElectionConfiguration().getRenewDeadline().toNanos()) {
                    // Not confirmed: isLeader() is already false since the renew deadline passed. The loss is reported as well, so
                    // that leases of this term stay invalid and a later confirmation counts as a new term.
                    setLeader(false, getAddressName(self));
                }
            } else {
                setLeader(false, getAddressName(self));
            }
        }
    }


    /**
     * Get the text of the local address; it only changes with a new channel.
     *
     * @param self the local address
     * @return the text of the address
     */
    private String getAddressName(Address self) {
        String name = addressName;
        if (name == null || namedAddress != self) {
            name = String.valueOf(self);
            // a concurrent caller may build the text once more, which is harmless
            addressName = name;
            namedAddress = self;
        }
        return name;
    }


    /**
     * Check if the members of the view confirm this node as their coordinator: at least one other member, or
     * {@code minimumMembers - 1} members when a quorum is set, answered a challenge sent within the renew deadline. A node
     * alone in its view needs no confirmation.
     *
     * @param view the cluster view, not empty
     * @param self the local address
     * @return true if the lead is confirmed
     */
    protected boolean isConfirmedByMembers(View view, Address self) {
        return getConfirmationNanos(view, self) != NOT_CONFIRMED;
    }


    /**
     * Get the time from which the members of the view confirm this node as their coordinator: the send time of the challenge
     * that the required number of members answered (the {@code required}-th newest answer within the renew deadline). A node
     * alone in its view confirms itself now.
     *
     * @param view the cluster view, not empty
     * @param self the local address
     * @return the nanoTime of the confirmation, or {@link #NOT_CONFIRMED}
     */
    private long getConfirmationNanos(View view, Address self) {
        long now = System.nanoTime();
        int others = view.size() - 1;
        if (others <= 0) {
            return now;
        }

        int required = Math.min(others, Math.max(1, getLeaderElectionConfiguration().getMinimumMembers() - 1));
        long windowNanos = getLeaderElectionConfiguration().getRenewDeadline().toNanos();
        List<Long> answers = new ArrayList<>(others);
        for (Address member : view.getMembers()) {
            Long heartbeatNanos = heartbeats.get(member);
            if (!member.equals(self) && heartbeatNanos != null && now - heartbeatNanos <= windowNanos) {
                answers.add(heartbeatNanos);
            }
        }
        if (answers.size() < required) {
            return NOT_CONFIRMED;
        }
        // newest first: the required-th newest answer is the time from which enough members confirm the lead
        answers.sort((a, b) -> Long.compare(b - a, 0L));
        return answers.get(required - 1);
    }


    /**
     * Send a challenge to the cluster when this node is the coordinator of the view and has other members: their answers
     * confirm the lead.
     *
     * @param ch the channel
     * @param view the cluster view
     * @param self the local address
     */
    protected void sendChallenge(JChannel ch, View view, Address self) {
        if (view == null || view.size() < 2 || !view.getMembers().get(0).equals(self)) {
            return;
        }
        send(ch, null, CHALLENGE, issueChallenge());
    }


    /**
     * Create a new challenge and remember when it was sent; challenges older than the renew deadline are dropped.
     *
     * @return the random number of the challenge
     */
    long issueChallenge() {
        long now = System.nanoTime();
        long windowNanos = getLeaderElectionConfiguration().getRenewDeadline().toNanos();
        challenges.values().removeIf(sent -> now - sent > windowNanos);
        long challenge = CHALLENGE_RANDOM.nextLong();
        challenges.put(challenge, now);
        return challenge;
    }


    /**
     * Send a challenge or a heartbeat.
     *
     * @param ch the channel
     * @param destination the receiver, or null for the whole cluster
     * @param marker the marker of the message
     * @param challenge the random number of the challenge
     */
    private void send(JChannel ch, Address destination, byte[] marker, long challenge) {
        try {
            ch.send(new BytesMessage(destination, ByteBuffer.allocate(MESSAGE_LENGTH).put(marker).putLong(challenge).array()));
        } catch (Exception e) {
            if (LOG.isDebugEnabled()) {
                LOG.debug("Could not send a message of cluster [{}] to [{}]: {}", getLeaderElectionInformation().getElectionGroupName(), destination, e.getMessage());
            }
        }
    }


    /**
     * Handle a challenge or a heartbeat of another member.
     *
     * @param ch the channel the message was received on
     * @param msg the message
     */
    void handleMessage(JChannel ch, Message msg) {
        Address source = msg.getSrc();
        if (source == null || source.equals(ch.getAddress()) || !(msg instanceof BytesMessage) || msg.getLength() != MESSAGE_LENGTH) {
            return;
        }
        long challenge = ByteBuffer.wrap(msg.getArray(), msg.getOffset() + HEARTBEAT.length, Long.BYTES).getLong();
        View view = ch.getView();
        if (hasMarker(msg, CHALLENGE)) {
            // only the coordinator of the own view is answered: a stale coordinator gets no confirmation
            if (view != null && !view.getMembers().isEmpty() && source.equals(view.getMembers().get(0))) {
                send(ch, source, HEARTBEAT, challenge);
            }
        } else if (hasMarker(msg, HEARTBEAT) && recordHeartbeat(source, challenge) && !super.isLeader()
                   && view != null && !view.getMembers().isEmpty()) {
            // confirm the lead at once instead of on the next check
            applyView(ch, view);
        }
    }


    /**
     * Record a heartbeat of a member that sees this node as its coordinator.
     *
     * @param member the member
     */
    void recordHeartbeat(Address member) {
        heartbeats.put(member, System.nanoTime());
    }


    /**
     * Record the answer of a member to a challenge: it only counts for a challenge this node sent within the renew deadline,
     * and it confirms the member as of the time the challenge was sent. A buffered or replayed answer to an older challenge
     * does not count.
     *
     * @param member the member
     * @param challenge the random number of the answered challenge
     * @return true if the answer was recorded
     */
    boolean recordHeartbeat(Address member, long challenge) {
        Long sent = challenges.get(challenge);
        if (sent == null || System.nanoTime() - sent > getLeaderElectionConfiguration().getRenewDeadline().toNanos()) {
            return false;
        }
        heartbeats.merge(member, sent, Math::max);
        return true;
    }


    /**
     * Test if a message starts with a marker.
     *
     * @param msg the message
     * @param marker the marker
     * @return true if the message starts with the marker
     */
    private static boolean hasMarker(Message msg, byte[] marker) {
        byte[] array = msg.getArray();
        int offset = msg.getOffset();
        for (int i = 0; i < marker.length; i++) {
            if (array[offset + i] != marker[i]) {
                return false;
            }
        }
        return true;
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
        closeSerialized(getLeaderElectionConfiguration().getCloseTimeout().toMillis(), this::closeNow);
    }


    /**
     * Close the elector. The channel stays in the cluster while leader tasks still run, so that no other node takes the lead
     * meanwhile; it leaves the cluster once they ended.
     *
     * @throws LeaderElectionException in case of an error
     */
    private void closeNow() throws LeaderElectionException {
        if (!closing.compareAndSet(false, true)) {
            return;
        }

        // Stop the periodic view checks and drain any in-flight check before resetting state.
        // This prevents a concurrent check from calling setLeader() after closing has already reset isLeader to null.
        LeaderElectionExecutors.PeriodicTask task = viewCheckTask;
        if (task != null) {
            task.cancel();
            try {
                task.awaitIdle(getLeaderElectionConfiguration().getCloseTimeout().toMillis());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            viewCheckTask = null;
        }

        // the callbacks of this channel compare it with the field: from now on they no longer report to this elector
        final JChannel channelToClose = channel;
        channel = null;
        String channelName = "";
        if (channelToClose != null && channelToClose.getAddress() != null) {
            channelName = "(" + channelToClose.getAddress() + ")";
        }

        boolean tasksFinished = false;
        try {
            tasksFinished = closeAndAwaitTasks();
        } finally {
            if (channelToClose != null) {
                final String exitedChannelName = channelName;
                releaseWhenTasksFinished(tasksFinished, "leader-election-network-release-" + getLeaderElectionInformation().getName(), () -> {
                    try {
                        channelToClose.close();
                    } catch (Exception e) {
                        // NOP
                    }
                    LOG.info("Exited from cluster [{}] {}.", getLeaderElectionInformation().getElectionGroupName(), exitedChannelName);
                });
            }
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
            NetworkLeaderElectionConfiguration configuration = getLeaderElectionConfiguration();
            if (configuration.getConfiguration() != null) {
                channel = new JChannel(configuration.getConfiguration());
            } else {
                ProtocolStackConfigurator protocolStackConfigurator = ConfiguratorFactory.getStackConfigurator(Global.DEFAULT_PROTOCOL_STACK);
                channel = new JChannel(protocolStackConfigurator);
            }

            selectTransportPort(channel);

            if (configuration.getEncryptionKey() != null) {
                addEncryption(channel, configuration.getEncryptionKey());
            } else if (configuration.getConfiguration() == null && UNPROTECTED_WARNING_LOGGED.compareAndSet(false, true)) {
                LOG.info("Network leader election runs without encryption: any host on the network can join the cluster and take the lead. "
                         + "Set an encryption key or a JGroups configuration with security protocols.");
            }

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
                    if (!isInitialized() || ch != NetworkLeaderElectorImpl.this.channel || newView == null || newView.getMembers().isEmpty()) {
                        return;
                    }
                    applyView(ch, newView);
                    sendChallenge(ch, newView, ch.getAddress());
                }


                /**
                 * @see org.jgroups.Receiver#receive(org.jgroups.Message)
                 */
                @Override
                public void receive(Message msg) {
                    if (ch == NetworkLeaderElectorImpl.this.channel) {
                        handleMessage(ch, msg);
                    }
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
     * Choose the transport port when the configuration leaves it to the operating system (bind port 0): a free port whose
     * failure detection port (transport port + FD_SOCK2 offset) can be bound, too. Without a free port after a few attempts the
     * operating system chooses as before.
     *
     * @param channel the channel, not connected
     */
    static void selectTransportPort(JChannel channel) {
        TP transport = channel.getProtocolStack().getTransport();
        FD_SOCK2 failureDetection = channel.getProtocolStack().findProtocol(FD_SOCK2.class);
        if (transport == null || failureDetection == null || transport.getBindPort() != 0) {
            return;
        }

        InetAddress bindAddress = transport.getBindAddress();
        int offset = failureDetection.getOffset();
        for (int i = 0; i < PORT_SELECTION_ATTEMPTS; i++) {
            // first the port the operating system chooses; it hands out ports in sequence, so the following attempts are random
            // ports of the dynamic range, which also step past large reserved blocks
            int candidate = 0;
            if (i > 0) {
                candidate = MIN_DYNAMIC_PORT + ThreadLocalRandom.current().nextInt(MAX_PORT - offset - MIN_DYNAMIC_PORT + 1);
            }

            int port;
            try (DatagramSocket probe = new DatagramSocket(new InetSocketAddress(bindAddress, candidate))) {
                port = probe.getLocalPort();
            } catch (IOException e) {
                // the candidate port is taken
                continue;
            }

            // FD_SOCK2 listens on all addresses (IPv4 and IPv6), so the port is checked the same way
            int failureDetectionPort = port + offset;
            if (failureDetectionPort <= MAX_PORT && isTcpPortFree(failureDetectionPort)) {
                transport.setBindPort(port);
                return;
            }
        }
    }


    /**
     * Test if a TCP port can be bound on all addresses.
     *
     * @param port the port
     * @return true if the port can be bound
     */
    private static boolean isTcpPortFree(int port) {
        try (ServerSocket serverSocket = new ServerSocket()) {
            serverSocket.bind(new InetSocketAddress(port));
            return true;
        } catch (IOException e) {
            return false;
        }
    }


    /**
     * Encrypt the cluster messages with the shared key. {@link SYM_ENCRYPT} is inserted directly above the transport, so that
     * the messages of all protocols are encrypted, including discovery and failure detection; {@link SERIALIZE} above it
     * packs each message with its headers (e.g. the sender) into the encrypted payload. The cipher runs in CBC mode with a
     * random IV per message, so equal messages never give equal cipher texts.
     *
     * @param channel the channel, not yet connected
     * @param encryptionKey the shared key
     * @throws IllegalStateException in case the stack already has an encryption protocol
     * @throws Exception in case the encryption could not be initialized
     */
    protected void addEncryption(JChannel channel, SecretKey encryptionKey) throws Exception {
        ProtocolStack stack = channel.getProtocolStack();
        Protocol existing = stack.findProtocol(Encrypt.class);
        if (existing != null) {
            throw new IllegalStateException("The JGroups configuration already contains [" + existing.getName() + "]: configure its key there or remove it to use the encryption key.");
        }

        String symAlgorithm = encryptionKey.getAlgorithm() + "/CBC/PKCS5Padding";
        int ivLength = Cipher.getInstance(symAlgorithm).getBlockSize();
        SYM_ENCRYPT encrypt = new SYM_ENCRYPT();
        encrypt.symAlgorithm(symAlgorithm);
        encrypt.symIvLength(ivLength);
        encrypt.setSecretKey(encryptionKey);
        Class<? extends Protocol> transport = stack.getTransport().getClass();
        stack.insertProtocol(encrypt, ProtocolStack.Position.ABOVE, transport);
        SERIALIZE serialize = new SERIALIZE();
        stack.insertProtocol(serialize, ProtocolStack.Position.ABOVE, SYM_ENCRYPT.class);

        // the stack is already initialized: insertProtocol() does not initialize the new protocols, connect() only starts them
        for (Protocol protocol : new Protocol[] {encrypt, serialize}) {
            protocol.setProtocolStack(stack);
            protocol.init();
        }
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
                if (!applyView(channel, view)) {
                    // the channel of this check was closed
                    return;
                }
                sendChallenge(channel, view, channel.getAddress());
            } catch (Exception e) {
                if (LOG.isDebugEnabled()) {
                    LOG.debug("Error occurred while verifying network cluster [{}]: {}", getLeaderElectionInformation().getUniqueName(), e.getMessage(), e);
                }
                LOG.info("Error occurred while verifying network cluster [{}]: {}, will retry on next election tick.", getLeaderElectionInformation().getUniqueName(), e.getMessage());
                // Step down when the cluster state is unknown so a broken channel
                // does not keep this node as leader indefinitely. Uses the stored flag because
                // the fenced isLeader() may already return false.
                if (NetworkLeaderElectorImpl.super.isLeader()) {
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
