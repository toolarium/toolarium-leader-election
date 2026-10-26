/*
 * NetworkChannelTest.java
 *
 * Copyright by toolarium, all rights reserved.
 */
package com.github.toolarium.leader.election.impl.network;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.github.toolarium.leader.election.ILeaderElector;
import com.github.toolarium.leader.election.LeaderElectionFactory;
import com.github.toolarium.leader.election.dto.LeaderElectionInformation;
import com.github.toolarium.leader.election.dto.network.NetworkLeaderElectionConfiguration;
import com.github.toolarium.leader.election.exception.LeaderElectionException;
import java.lang.reflect.Field;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import javax.crypto.KeyGenerator;
import org.jgroups.Global;
import org.jgroups.JChannel;
import org.jgroups.View;
import org.jgroups.conf.ConfiguratorFactory;
import org.jgroups.protocols.FD_SOCK2;
import org.jgroups.protocols.SYM_ENCRYPT;
import org.junit.jupiter.api.Test;


/**
 * Tests the channel handling of the NETWORK strategy: custom JGroups configurations, a channel that cannot be created, and
 * the periodic view check when the channel delivers no view or fails.
 *
 * @author patrick
 */
public class NetworkChannelTest {
    private static final String CHECK_THREAD_PREFIX = "leader-election-network-";


    /**
     * A custom JGroups configuration replaces the default stack and can be combined with an encryption key.
     *
     * @throws Exception in case of an error
     */
    @Test
    public void testCustomConfiguration() throws Exception {
        KeyGenerator generator = KeyGenerator.getInstance("AES");
        generator.init(128);
        ILeaderElector elector = LeaderElectionFactory.builder().name("network-custom-configuration").timeout(Duration.ofSeconds(10))
                .network().configuration("udp.xml").encryptionKey(generator.generateKey()).build();
        try {
            assertTrue(elector.elect(Duration.ofSeconds(10)), "A single node with a custom configuration must lead");
            JChannel channel = readChannel(elector);
            assertNotNull(channel.getProtocolStack().findProtocol(SYM_ENCRYPT.class), "The encryption must be added to the custom stack");
        } finally {
            elector.close();
        }
    }


    /**
     * The transport port is chosen so that the failure detection listens on transport port + offset: no member has to try
     * further ports to connect, and reserved port blocks do not prevent joining.
     *
     * @throws Exception in case of an error
     */
    @Test
    public void testFailureDetectionPortIsFree() throws Exception {
        ILeaderElector elector = LeaderElectionFactory.builder().name("network-port-selection").timeout(Duration.ofSeconds(10)).network().build();
        try {
            assertTrue(elector.elect(Duration.ofSeconds(10)));
            JChannel channel = readChannel(elector);
            FD_SOCK2 failureDetection = channel.getProtocolStack().findProtocol(FD_SOCK2.class);
            int transportPort = channel.getProtocolStack().getTransport().getBindPort();
            assertTrue(transportPort > 0, "The transport port must be chosen");
            assertEquals(transportPort + failureDetection.getOffset(), failureDetection.getActualBindPort(), "The failure detection must listen on its first port");
        } finally {
            elector.close();
        }
    }


    /**
     * A configuration that cannot be loaded makes elect() fail.
     *
     * @throws Exception in case of an error
     */
    @Test
    public void testInvalidConfigurationFails() throws Exception {
        ILeaderElector elector = LeaderElectionFactory.builder().name("network-invalid-configuration").timeout(Duration.ofSeconds(10))
                .network().configuration("does-not-exist.xml").build();
        try {
            assertThrows(LeaderElectionException.class, () -> elector.elect(Duration.ofSeconds(5)));
            assertFalse(elector.isLeader());
        } finally {
            elector.close();
        }
    }


    /**
     * The leader steps down at once when the view check fails, without waiting for the renew deadline.
     *
     * @throws Exception in case of an error
     */
    @Test
    public void testLeaderStepsDownWhenViewCheckFails() throws Exception {
        // long renew deadline: a step-down within a few seconds can only come from the failing check
        TestChannelElector elector = new TestChannelElector("network-failing-check", Duration.ofSeconds(30), Duration.ofSeconds(20), Duration.ofMillis(500));
        try {
            assertTrue(elector.elect(Duration.ofSeconds(10)));
            elector.channel.failChecks = true;
            assertTrue(await(() -> !elector.isLeader(), 3000), "A failing view check must step down");
        } finally {
            elector.close();
        }
    }


    /**
     * Without any view, elect() returns false once the stabilisation window has passed instead of blocking.
     *
     * @throws Exception in case of an error
     */
    @Test
    public void testElectReturnsWithoutView() throws Exception {
        TestChannelElector elector = new TestChannelElector("network-no-view", Duration.ofSeconds(10), Duration.ofSeconds(7), Duration.ofMillis(500));
        elector.noView = true;
        try {
            assertFalse(elector.elect(Duration.ofSeconds(15)), "elect() must return false when no view arrives");
        } finally {
            elector.close();
        }
    }


    /**
     * When the view check fails from the start, elect() returns false once the stabilisation window has passed.
     *
     * @throws Exception in case of an error
     */
    @Test
    public void testElectReturnsWhenViewCheckFails() throws Exception {
        TestChannelElector elector = new TestChannelElector("network-failing-start", Duration.ofSeconds(10), Duration.ofSeconds(7), Duration.ofMillis(500));
        elector.noView = true;
        elector.failChecks = true;
        try {
            assertFalse(elector.elect(Duration.ofSeconds(15)), "elect() must return false when every view check fails");
        } finally {
            elector.close();
        }
    }


    /**
     * Read the channel of an elector.
     *
     * @param elector the elector
     * @return the channel
     * @throws Exception in case of an error
     */
    private static JChannel readChannel(ILeaderElector elector) throws Exception {
        Field field = NetworkLeaderElectorImpl.class.getDeclaredField("channel");
        field.setAccessible(true);
        return (JChannel) field.get(elector);
    }


    /**
     * Wait until a condition is true.
     *
     * @param condition the condition
     * @param timeoutMillis the maximum time to wait
     * @return true if the condition is true
     * @throws InterruptedException if interrupted
     */
    private static boolean await(java.util.function.BooleanSupplier condition, long timeoutMillis) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis);
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() > deadline) {
                return false;
            }
            Thread.sleep(20);
        }
        return true;
    }


    /**
     * An elector whose channel can hide the view or fail the periodic view check.
     */
    private static final class TestChannelElector extends NetworkLeaderElectorImpl {
        private volatile boolean noView;
        private volatile boolean failChecks;
        private volatile TestChannel channel;


        /**
         * Constructor for TestChannelElector
         *
         * @param name the election name
         * @param timeout the timeout
         * @param renewDeadline the renew deadline
         * @param retryPeriod the retry period
         */
        TestChannelElector(String name, Duration timeout, Duration renewDeadline, Duration retryPeriod) {
            super(new LeaderElectionInformation(name), new NetworkLeaderElectionConfiguration(timeout, renewDeadline, retryPeriod));
        }


        /**
         * @see com.github.toolarium.leader.election.impl.network.NetworkLeaderElectorImpl#createChannel()
         */
        @Override
        protected JChannel createChannel() {
            try {
                TestChannel ch = new TestChannel();
                ch.connect(getLeaderElectionInformation().getElectionGroupName());
                ch.noView = noView;
                ch.failChecks = failChecks;
                channel = ch;
                return ch;
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        }
    }


    /**
     * A channel that can hide the view, or fail when the periodic view check reads it.
     */
    private static final class TestChannel extends JChannel {
        private volatile boolean noView;
        private volatile boolean failChecks;


        /**
         * Constructor for TestChannel
         *
         * @throws Exception in case of an error
         */
        TestChannel() throws Exception {
            super(ConfiguratorFactory.getStackConfigurator(Global.DEFAULT_PROTOCOL_STACK));
            // same as the elector: a transport port whose failure detection port is free
            NetworkLeaderElectorImpl.selectTransportPort(this);
        }


        /**
         * @see org.jgroups.JChannel#getView()
         */
        @Override
        public View getView() {
            if (failChecks && Thread.currentThread().getName().startsWith(CHECK_THREAD_PREFIX)) {
                throw new IllegalStateException("Simulated view check failure");
            }
            if (noView) {
                return null;
            }
            return super.getView();
        }
    }
}
