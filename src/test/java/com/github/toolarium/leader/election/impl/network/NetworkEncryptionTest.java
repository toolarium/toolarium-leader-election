/*
 * NetworkEncryptionTest.java
 *
 * Copyright by toolarium, all rights reserved.
 */
package com.github.toolarium.leader.election.impl.network;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.github.toolarium.leader.election.ILeaderElector;
import com.github.toolarium.leader.election.LeaderElectionFactory;
import com.github.toolarium.leader.election.dto.LeaderElectionInformation;
import com.github.toolarium.leader.election.dto.network.NetworkLeaderElectionConfiguration;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.KeyStore;
import java.time.Duration;
import java.util.List;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import org.jgroups.Global;
import org.jgroups.JChannel;
import org.jgroups.conf.ConfiguratorFactory;
import org.jgroups.protocols.SYM_ENCRYPT;
import org.jgroups.protocols.pbcast.NAKACK2;
import org.jgroups.stack.Protocol;
import org.junit.jupiter.api.Test;


/**
 * Tests the encryption of the NETWORK strategy.
 *
 * @author patrick
 */
public class NetworkEncryptionTest {
    private static final String PASSWORD = "secret-password";
    private static final String ALIAS = "leader-election";


    /**
     * The key is read from a PKCS12 keystore; wrong input is rejected with a clear error.
     *
     * @throws Exception in case of an error
     */
    @Test
    public void testLoadEncryptionKey() throws Exception {
        SecretKey key = createKey();
        String keystore = writeKeystore(key, "PKCS12");

        assertEquals(key, NetworkLeaderElectionConfiguration.loadEncryptionKey(keystore, PASSWORD.toCharArray(), ALIAS));
        assertEquals(key, NetworkLeaderElectionConfiguration.loadEncryptionKey(writeKeystore(key, "JCEKS"), PASSWORD.toCharArray(), ALIAS));
        assertThrows(IllegalArgumentException.class, () -> NetworkLeaderElectionConfiguration.loadEncryptionKey(keystore, "wrong".toCharArray(), ALIAS));
        assertThrows(IllegalArgumentException.class, () -> NetworkLeaderElectionConfiguration.loadEncryptionKey(keystore, PASSWORD.toCharArray(), "unknown"));
        assertThrows(IllegalArgumentException.class, () -> NetworkLeaderElectionConfiguration.loadEncryptionKey("missing.p12", PASSWORD.toCharArray(), ALIAS));
        assertThrows(IllegalArgumentException.class, () -> NetworkLeaderElectionConfiguration.loadEncryptionKey(null, PASSWORD.toCharArray(), ALIAS));

        NetworkLeaderElectionConfiguration config = new NetworkLeaderElectionConfiguration();
        config.setEncryptionKey(keystore, PASSWORD.toCharArray(), ALIAS);
        assertTrue(config.toString().contains("encrypted=true"));
        assertFalse(config.toString().contains(PASSWORD));
    }


    /**
     * The encryption is inserted directly below NAKACK2; a stack that already has an encryption protocol is rejected.
     *
     * @throws Exception in case of an error
     */
    @Test
    public void testEncryptionIsInsertedBelowNakack() throws Exception {
        NetworkLeaderElectorImpl elector = new NetworkLeaderElectorImpl(new LeaderElectionInformation("network-encryption-stack"), new NetworkLeaderElectionConfiguration());
        try (JChannel channel = new JChannel(ConfiguratorFactory.getStackConfigurator(Global.DEFAULT_PROTOCOL_STACK))) {
            SecretKey key = createKey();
            elector.addEncryption(channel, key);

            List<Protocol> protocols = channel.getProtocolStack().getProtocols(); // top to bottom
            int nakack = protocols.indexOf(channel.getProtocolStack().findProtocol(NAKACK2.class));
            assertTrue(protocols.get(nakack + 1) instanceof SYM_ENCRYPT, "SYM_ENCRYPT must be directly below NAKACK2");
            assertEquals(key, ((SYM_ENCRYPT) protocols.get(nakack + 1)).secretKey());

            assertThrows(IllegalStateException.class, () -> elector.addEncryption(channel, key));
        } finally {
            elector.close();
        }
    }


    /**
     * A stack without NAKACK2 is rejected: there is no place for the encryption.
     *
     * @throws Exception in case of an error
     */
    @Test
    public void testStackWithoutNakackIsRejected() throws Exception {
        NetworkLeaderElectorImpl elector = new NetworkLeaderElectorImpl(new LeaderElectionInformation("network-encryption-no-nakack"), new NetworkLeaderElectionConfiguration());
        try (JChannel channel = new JChannel(ConfiguratorFactory.getStackConfigurator(Global.DEFAULT_PROTOCOL_STACK))) {
            channel.getProtocolStack().removeProtocol(NAKACK2.class);
            assertThrows(IllegalStateException.class, () -> elector.addEncryption(channel, createKey()));
        } finally {
            elector.close();
        }
    }


    /**
     * Two nodes with the same key form one cluster with one leader; a node with another key cannot join and leads alone.
     *
     * @throws Exception in case of an error
     */
    @Test
    public void testOnlyNodesWithTheKeyJoin() throws Exception {
        SecretKey key = createKey();
        ILeaderElector first = createElector(key);
        ILeaderElector second = createElector(key);
        ILeaderElector foreign = createElector(createKey());
        try {
            assertTrue(first.elect(Duration.ofSeconds(8)), "The first node must lead");
            second.elect(Duration.ofSeconds(12));
            assertFalse(second.isLeader(), "A node with the same key joins the cluster and does not lead");
            assertTrue(first.isLeader());

            assertTrue(foreign.elect(Duration.ofSeconds(12)), "A node with another key cannot join and leads its own cluster");
            assertTrue(first.isLeader(), "The node with another key must not take the lead of the cluster");
        } finally {
            foreign.close();
            second.close();
            first.close();
        }
    }


    /**
     * Create an elector of the shared test cluster.
     *
     * @param key the encryption key
     * @return the elector
     * @throws Exception in case of an error
     */
    private ILeaderElector createElector(SecretKey key) throws Exception {
        return LeaderElectionFactory.builder().name("network-encryption-cluster").timeout(Duration.ofSeconds(10)).network().encryptionKey(key).build();
    }


    /**
     * Create an AES key.
     *
     * @return the key
     * @throws Exception in case of an error
     */
    private static SecretKey createKey() throws Exception {
        KeyGenerator generator = KeyGenerator.getInstance("AES");
        generator.init(128);
        return generator.generateKey();
    }


    /**
     * Write a keystore with the key.
     *
     * @param key the key
     * @param type the keystore type
     * @return the keystore file
     * @throws Exception in case of an error
     */
    private static String writeKeystore(SecretKey key, String type) throws Exception {
        KeyStore keyStore = KeyStore.getInstance(type);
        keyStore.load(null, null);
        keyStore.setEntry(ALIAS, new KeyStore.SecretKeyEntry(key), new KeyStore.PasswordProtection(PASSWORD.toCharArray()));
        Path file = Paths.get("build", "network-encryption-" + System.nanoTime() + "." + type.toLowerCase());
        try (OutputStream out = Files.newOutputStream(file)) {
            keyStore.store(out, PASSWORD.toCharArray());
        }
        return file.toString();
    }
}
