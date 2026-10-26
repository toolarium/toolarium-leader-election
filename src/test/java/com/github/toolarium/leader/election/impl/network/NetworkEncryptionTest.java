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
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.KeyStore;
import java.time.Duration;
import java.util.List;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import org.jgroups.Global;
import org.jgroups.JChannel;
import org.jgroups.conf.ConfiguratorFactory;
import org.jgroups.protocols.SERIALIZE;
import org.jgroups.protocols.SYM_ENCRYPT;
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
     * Only an AES key of 128, 192 or 256 bits is accepted: JGroups sends a hash of the key with every message, so a weak key
     * could be found from it.
     *
     * @throws Exception in case of an error
     */
    @Test
    public void testWeakKeysAreRejected() throws Exception {
        NetworkLeaderElectionConfiguration config = new NetworkLeaderElectionConfiguration();
        config.setEncryptionKey(createKey());
        config.setEncryptionKey(null);

        SecretKey des = KeyGenerator.getInstance("DESede").generateKey();
        assertThrows(IllegalArgumentException.class, () -> config.setEncryptionKey(des), "A non-AES key must be rejected");
        SecretKey shortKey = new SecretKeySpec("password".getBytes(StandardCharsets.UTF_8), "AES");
        assertThrows(IllegalArgumentException.class, () -> config.setEncryptionKey(shortKey), "A key of 64 bits must be rejected");
        assertThrows(IllegalArgumentException.class, () -> LeaderElectionFactory.builder().name("weak-key").network().encryptionKey(shortKey));
        assertThrows(IllegalArgumentException.class, () -> NetworkLeaderElectionConfiguration.loadEncryptionKey(writeKeystore(des, "JCEKS"), PASSWORD.toCharArray(), ALIAS),
                "A weak key from a keystore must be rejected");
    }


    /**
     * The encryption is inserted directly above the transport with SERIALIZE above it, so that all messages including their
     * headers are encrypted; it uses CBC mode with an IV. A stack that already has an encryption protocol is rejected.
     *
     * @throws Exception in case of an error
     */
    @Test
    public void testEncryptionIsInsertedAboveTransport() throws Exception {
        NetworkLeaderElectorImpl elector = new NetworkLeaderElectorImpl(new LeaderElectionInformation("network-encryption-stack"), new NetworkLeaderElectionConfiguration());
        try (JChannel channel = new JChannel(ConfiguratorFactory.getStackConfigurator(Global.DEFAULT_PROTOCOL_STACK))) {
            SecretKey key = createKey();
            elector.addEncryption(channel, key);

            List<Protocol> protocols = channel.getProtocolStack().getProtocols(); // top to bottom
            int transport = protocols.indexOf(channel.getProtocolStack().getTransport());
            assertTrue(protocols.get(transport - 1) instanceof SYM_ENCRYPT, "SYM_ENCRYPT must be directly above the transport");
            assertTrue(protocols.get(transport - 2) instanceof SERIALIZE, "SERIALIZE must be directly above SYM_ENCRYPT");
            SYM_ENCRYPT encrypt = (SYM_ENCRYPT) protocols.get(transport - 1);
            assertEquals(key, encrypt.secretKey());
            assertEquals("AES/CBC/PKCS5Padding", encrypt.symAlgorithm());
            assertEquals(16, encrypt.simIvLength());

            assertThrows(IllegalStateException.class, () -> elector.addEncryption(channel, key));
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
