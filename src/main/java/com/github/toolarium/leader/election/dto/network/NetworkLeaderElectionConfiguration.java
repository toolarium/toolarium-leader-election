/*
 * NetworkLeaderElectionConfiguration.java
 *
 * Copyright by toolarium, all rights reserved.
 */
package com.github.toolarium.leader.election.dto.network;

import com.github.toolarium.leader.election.dto.LeaderElectionConfiguration;
import java.io.File;
import java.security.KeyStore;
import java.time.Duration;
import java.util.Objects;
import javax.crypto.SecretKey;


/**
 * The leader election network configuration.
 *
 * <p>The {@code minimumMembers} value is the quorum: a node only takes the lead when it is the
 * coordinator of a cluster view with at least this many members. Set it to a majority of the
 * expected cluster size ({@code n / 2 + 1}) to prevent two leaders during a network partition;
 * the minority side then has no leader. The default of 1 keeps the lead on every side of a
 * partition.
 *
 * <p>Security: by default the cluster is neither authenticated nor encrypted, so any host on the network can join it
 * and take the lead. With an {@code encryptionKey} all cluster messages are encrypted with this shared key; a node
 * without the key can neither read the messages nor join the cluster. A custom JGroups {@code configuration}
 * replaces the default protocol stack, e.g. to use TCP, a specific network interface or other security protocols.
 *
 * @author patrick
 */
public class NetworkLeaderElectionConfiguration extends LeaderElectionConfiguration {
    /** The default minimum number of cluster members: no quorum. */
    public static final int DEFAULT_MINIMUM_MEMBERS = 1;
    private int minimumMembers;
    private String configuration;
    private SecretKey encryptionKey;


    /**
     * Constructor for NetworkLeaderElectionConfiguration
     *
     * @throws IllegalArgumentException In case of a parameter failure
     */
    public NetworkLeaderElectionConfiguration() throws IllegalArgumentException {
        super();
        minimumMembers = DEFAULT_MINIMUM_MEMBERS;
    }


    /**
     * Constructor for NetworkLeaderElectionConfiguration
     *
     * @param timeout the timeout
     * @param renewDeadline the renew deadline
     * @param retryPeriod the retry period
     * @throws IllegalArgumentException In case of a parameter failure
     */
    public NetworkLeaderElectionConfiguration(Duration timeout, Duration renewDeadline, Duration retryPeriod) throws IllegalArgumentException {
        super(timeout, renewDeadline, retryPeriod);
        minimumMembers = DEFAULT_MINIMUM_MEMBERS;
    }


    /**
     * Constructor for NetworkLeaderElectionConfiguration
     *
     * @param minimumMembers the minimum number of cluster members required to take the lead
     * @param timeout the timeout
     * @param renewDeadline the renew deadline
     * @param retryPeriod the retry period
     * @throws IllegalArgumentException In case of a parameter failure
     */
    public NetworkLeaderElectionConfiguration(int minimumMembers, Duration timeout, Duration renewDeadline, Duration retryPeriod) throws IllegalArgumentException {
        super(timeout, renewDeadline, retryPeriod);
        setMinimumMembers(minimumMembers);
    }


    /**
     * Gets the minimum number of cluster members required to take the lead.
     *
     * @return the minimum number of cluster members
     */
    public int getMinimumMembers() {
        return minimumMembers;
    }


    /**
     * Sets the minimum number of cluster members required to take the lead.
     *
     * @param minimumMembers the minimum number of cluster members, at least 1
     * @throws IllegalArgumentException In case the value is less than 1
     */
    public void setMinimumMembers(int minimumMembers) throws IllegalArgumentException {
        if (minimumMembers < 1) {
            throw new IllegalArgumentException("Invalid minimumMembers [" + minimumMembers + "]: must be at least 1!");
        }
        this.minimumMembers = minimumMembers;
    }


    /**
     * Gets the custom JGroups configuration.
     *
     * @return the JGroups configuration (file, classpath resource or URL), or {@code null} for the default stack
     */
    public String getConfiguration() {
        return configuration;
    }


    /**
     * Sets a custom JGroups configuration that replaces the default protocol stack.
     *
     * @param configuration the JGroups configuration (file, classpath resource or URL), or {@code null} for the default stack
     */
    public void setConfiguration(String configuration) {
        this.configuration = configuration;
    }


    /**
     * Gets the shared key that encrypts the cluster messages.
     *
     * @return the encryption key, or {@code null} if the messages are not encrypted
     */
    public SecretKey getEncryptionKey() {
        return encryptionKey;
    }


    /**
     * Sets the shared key that encrypts the cluster messages. All nodes of the cluster need the same key.
     *
     * @param encryptionKey the encryption key, or {@code null} to not encrypt
     */
    public void setEncryptionKey(SecretKey encryptionKey) {
        this.encryptionKey = encryptionKey;
    }


    /**
     * Sets the shared key that encrypts the cluster messages from a keystore (PKCS12 or JCEKS). All nodes of the
     * cluster need the same key. The password array is not changed; the caller can clear it afterwards.
     *
     * @param keystore the keystore file
     * @param password the password of the keystore and the key
     * @param alias the alias of the secret key entry
     * @throws IllegalArgumentException In case the key could not be read
     */
    public void setEncryptionKey(String keystore, char[] password, String alias) throws IllegalArgumentException {
        this.encryptionKey = loadEncryptionKey(keystore, password, alias);
    }


    /**
     * Read a secret key from a keystore (PKCS12 or JCEKS). The password array is not changed; the caller can clear it afterwards.
     *
     * @param keystore the keystore file
     * @param password the password of the keystore and the key
     * @param alias the alias of the secret key entry
     * @return the secret key
     * @throws IllegalArgumentException In case the key could not be read
     */
    public static SecretKey loadEncryptionKey(String keystore, char[] password, String alias) throws IllegalArgumentException {
        if (keystore == null || password == null || alias == null) {
            throw new IllegalArgumentException("Invalid encryption key: keystore, password and alias must not be null!");
        }

        KeyStore.Entry entry;
        KeyStore.PasswordProtection protection = new KeyStore.PasswordProtection(password);
        try {
            KeyStore keyStore = KeyStore.getInstance(new File(keystore), password);
            entry = keyStore.getEntry(alias, protection);
        } catch (Exception e) {
            throw new IllegalArgumentException("Could not read the encryption key [" + alias + "] from keystore [" + keystore + "]: " + e.getMessage(), e);
        } finally {
            try {
                // clears the copy of the password held by the protection
                protection.destroy();
            } catch (Exception e) {
                // NOP
            }
        }

        if (!(entry instanceof KeyStore.SecretKeyEntry)) {
            throw new IllegalArgumentException("Keystore [" + keystore + "] has no secret key with alias [" + alias + "]!");
        }
        return ((KeyStore.SecretKeyEntry) entry).getSecretKey();
    }


    /**
     * @see com.github.toolarium.leader.election.dto.LeaderElectionConfiguration#hashCode()
     */
    @Override
    public int hashCode() {
        return Objects.hash(super.hashCode(), minimumMembers, configuration, encryptionKey);
    }


    /**
     * @see com.github.toolarium.leader.election.dto.LeaderElectionConfiguration#equals(java.lang.Object)
     */
    @Override
    public boolean equals(Object obj) {
        if (!super.equals(obj)) {
            return false;
        }
        NetworkLeaderElectionConfiguration other = (NetworkLeaderElectionConfiguration) obj;
        return minimumMembers == other.minimumMembers && Objects.equals(configuration, other.configuration) && Objects.equals(encryptionKey, other.encryptionKey);
    }


    /**
     * @see com.github.toolarium.leader.election.dto.LeaderElectionConfiguration#toString()
     */
    @Override
    public String toString() {
        return "NetworkLeaderElectionConfiguration [timeout=" + getTimeout() + ", renewDeadline=" + getRenewDeadline() + ", retryPeriod=" + getRetryPeriod()
            + ", closeTimeout=" + getCloseTimeout() + ", minimumMembers=" + minimumMembers + ", configuration=" + configuration
            + ", encrypted=" + (encryptionKey != null) + "]";
    }
}
