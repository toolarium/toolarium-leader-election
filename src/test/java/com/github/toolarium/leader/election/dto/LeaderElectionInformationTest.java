/*
 * LeaderElectionInformationTest.java
 *
 * Copyright by toolarium, all rights reserved.
 */
package com.github.toolarium.leader.election.dto;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;


/**
 * Test the leader election information.
 *
 * @author patrick
 */
public class LeaderElectionInformationTest {
    private static final String NAMESPACE = "ns";
    private static final String APP_NAME = "myapp";
    private static final String NODE_1 = "node-1";

    /**
     * Test full constructor and getters.
     */
    @Test
    public void testFullConstructor() {
        LeaderElectionInformation info = new LeaderElectionInformation(NAMESPACE, APP_NAME, NODE_1);
        assertEquals(NAMESPACE, info.getNamespace());
        assertEquals(APP_NAME, info.getName());
        assertEquals(NODE_1, info.getIdentity());
    }


    /**
     * Test identity-only constructor sets empty namespace and name.
     */
    @Test
    public void testIdentityOnlyConstructor() {
        LeaderElectionInformation info = new LeaderElectionInformation(NODE_1);
        assertNull(info.getNamespace());
        assertEquals(NODE_1, info.getName());
        assertNotNull(info.getIdentity());
        assertFalse(info.getIdentity().trim().isEmpty());
    }


    /**
     * Test getUniqueName with all fields set.
     */
    @Test
    public void testGetUniqueNameAllFields() {
        LeaderElectionInformation info = new LeaderElectionInformation(NAMESPACE, APP_NAME, NODE_1);
        assertEquals("ns.myapp.node-1", info.getUniqueName());
    }


    /**
     * Test getUniqueName with only name set (single-arg constructor); identity is auto-generated.
     */
    @Test
    public void testGetUniqueNameNameOnly() {
        LeaderElectionInformation info = new LeaderElectionInformation(NODE_1);
        assertTrue(info.getUniqueName().startsWith(NODE_1 + "."));
    }


    /**
     * Test getUniqueName with namespace and identity only (empty name).
     */
    @Test
    public void testGetUniqueNameNamespaceAndIdentity() {
        LeaderElectionInformation info = new LeaderElectionInformation(NAMESPACE, "", NODE_1);
        assertEquals("ns.node-1", info.getUniqueName());
    }


    /**
     * Test getUniqueName with name and identity only (empty namespace).
     */
    @Test
    public void testGetUniqueNameNameAndIdentity() {
        LeaderElectionInformation info = new LeaderElectionInformation("", APP_NAME, NODE_1);
        assertEquals("myapp.node-1", info.getUniqueName());
    }


    /**
     * Test setters.
     */
    @Test
    public void testSetters() {
        LeaderElectionInformation info = new LeaderElectionInformation(NAMESPACE, APP_NAME, NODE_1);
        info.setNamespace("ns2");
        info.setName("otherapp");
        info.setIdentity("node-2");
        assertEquals("ns2", info.getNamespace());
        assertEquals("otherapp", info.getName());
        assertEquals("node-2", info.getIdentity());
        assertEquals("ns2.otherapp.node-2", info.getUniqueName());
    }


    /**
     * Test equals and hashCode.
     */
    @Test
    public void testEqualsAndHashCode() {
        LeaderElectionInformation a = new LeaderElectionInformation(NAMESPACE, APP_NAME, NODE_1);
        LeaderElectionInformation b = new LeaderElectionInformation(NAMESPACE, APP_NAME, NODE_1);
        LeaderElectionInformation c = new LeaderElectionInformation(NAMESPACE, APP_NAME, "node-2");

        assertEquals(a, b);
        assertEquals(a.hashCode(), b.hashCode());
        assertNotEquals(a, c);
        assertEquals(a, a);
        assertNotEquals(a, null);
        assertNotEquals(a, "other");
    }


    /**
     * Test getElectionGroupName with all fields set; identity is excluded from the key so that
     * all per-node identities compete for the same database row.
     */
    @Test
    public void testGetElectionGroupNameAllFields() {
        LeaderElectionInformation info = new LeaderElectionInformation(NAMESPACE, APP_NAME, NODE_1);
        assertEquals("ns.myapp", info.getElectionGroupName());
    }


    /**
     * Test getElectionGroupName with identity only; falls back to identity so that the
     * single-argument constructor continues to work as the election group key.
     */
    @Test
    public void testGetElectionGroupNameIdentityOnly() {
        LeaderElectionInformation info = new LeaderElectionInformation(NODE_1);
        assertEquals(NODE_1, info.getElectionGroupName());
    }


    /**
     * Test getElectionGroupName with namespace and name only (no identity).
     */
    @Test
    public void testGetElectionGroupNameNamespaceAndName() {
        LeaderElectionInformation info = new LeaderElectionInformation(NAMESPACE, APP_NAME, "");
        assertEquals("ns.myapp", info.getElectionGroupName());
    }


    /**
     * Test getElectionGroupName with namespace only.
     */
    @Test
    public void testGetElectionGroupNameNamespaceOnly() {
        LeaderElectionInformation info = new LeaderElectionInformation(NAMESPACE, "", "");
        assertEquals(NAMESPACE, info.getElectionGroupName());
    }


    /**
     * Test toString.
     */
    @Test
    public void testToString() {
        LeaderElectionInformation info = new LeaderElectionInformation(NAMESPACE, APP_NAME, NODE_1);
        String str = info.toString();
        // username is the OS user at test runtime; verify the fixed parts only
        org.junit.jupiter.api.Assertions.assertTrue(str.startsWith("LeaderElectionInformation [namespace=ns, name=myapp, identity=node-1, username="));
        org.junit.jupiter.api.Assertions.assertTrue(str.endsWith("]"));
    }


    /**
     * Test that identity at exactly the maximum length is accepted.
     */
    @Test
    public void testIdentityAtMaxLength() {
        String maxIdentity = "a".repeat(LeaderElectionInformation.MAX_IDENTITY_LENGTH);
        assertDoesNotThrow(() -> new LeaderElectionInformation(NAMESPACE, APP_NAME, maxIdentity));
    }


    /**
     * Test that identity exceeding the maximum length is rejected.
     */
    @Test
    public void testIdentityExceedsMaxLength() {
        String tooLong = "a".repeat(LeaderElectionInformation.MAX_IDENTITY_LENGTH + 1);
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> new LeaderElectionInformation(NAMESPACE, APP_NAME, tooLong));
        assertTrue(ex.getMessage().contains("identity"));
        assertTrue(ex.getMessage().contains("255"));
    }


    /**
     * Test that setIdentity rejects values exceeding the maximum length.
     */
    @Test
    public void testSetIdentityExceedsMaxLength() {
        LeaderElectionInformation info = new LeaderElectionInformation(NAMESPACE, APP_NAME, NODE_1);
        String tooLong = "a".repeat(LeaderElectionInformation.MAX_IDENTITY_LENGTH + 1);
        assertThrows(IllegalArgumentException.class, () -> info.setIdentity(tooLong));
        assertEquals(NODE_1, info.getIdentity());
    }


    /**
     * Test that username at exactly the maximum length is accepted.
     */
    @Test
    public void testUsernameAtMaxLength() {
        LeaderElectionInformation info = new LeaderElectionInformation(NAMESPACE, APP_NAME, NODE_1);
        String maxUsername = "u".repeat(LeaderElectionInformation.MAX_USERNAME_LENGTH);
        assertDoesNotThrow(() -> info.setUsername(maxUsername));
        assertEquals(maxUsername, info.getUsername());
    }


    /**
     * Test that username exceeding the maximum length is rejected.
     */
    @Test
    public void testUsernameExceedsMaxLength() {
        LeaderElectionInformation info = new LeaderElectionInformation(NAMESPACE, APP_NAME, NODE_1);
        String originalUsername = info.getUsername();
        String tooLong = "u".repeat(LeaderElectionInformation.MAX_USERNAME_LENGTH + 1);
        assertThrows(IllegalArgumentException.class, () -> info.setUsername(tooLong));
        assertEquals(originalUsername, info.getUsername());
    }


    /**
     * Test that the combined election group name at exactly the maximum length is accepted.
     */
    @Test
    public void testElectionGroupNameAtMaxLength() {
        // "ns." prefix = 3 chars, so name can be 512 - 3 = 509 chars
        String longName = "n".repeat(LeaderElectionInformation.MAX_ELECTION_GROUP_NAME_LENGTH - 3);
        assertDoesNotThrow(() -> new LeaderElectionInformation(NAMESPACE, longName, NODE_1));
    }


    /**
     * Test that the combined election group name exceeding the maximum length is rejected.
     */
    @Test
    public void testElectionGroupNameExceedsMaxLength() {
        // "ns." prefix = 3 chars, so name of 510 chars gives 513 total
        String longName = "n".repeat(LeaderElectionInformation.MAX_ELECTION_GROUP_NAME_LENGTH - 2);
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> new LeaderElectionInformation(NAMESPACE, longName, NODE_1));
        assertTrue(ex.getMessage().contains("512"));
    }


    /**
     * Test that setNamespace rejects a value that would push the combined group name over the limit,
     * and that the original value is preserved on rejection.
     */
    @Test
    public void testSetNamespaceExceedsGroupNameLimit() {
        LeaderElectionInformation info = new LeaderElectionInformation(NAMESPACE, APP_NAME, NODE_1);
        // "myapp" = 5 chars + "." = 6, so namespace of 507 gives 512 (ok), 508 gives 513 (rejected)
        String tooLongNs = "x".repeat(LeaderElectionInformation.MAX_ELECTION_GROUP_NAME_LENGTH - 4);
        assertThrows(IllegalArgumentException.class, () -> info.setNamespace(tooLongNs));
        assertEquals(NAMESPACE, info.getNamespace());
    }


    /**
     * Test that setName rejects a value that would push the combined group name over the limit,
     * and that the original value is preserved on rejection.
     */
    @Test
    public void testSetNameExceedsGroupNameLimit() {
        LeaderElectionInformation info = new LeaderElectionInformation(NAMESPACE, APP_NAME, NODE_1);
        // "ns." = 3 chars, so name of 510 gives 513 (rejected)
        String tooLongName = "y".repeat(LeaderElectionInformation.MAX_ELECTION_GROUP_NAME_LENGTH - 1);
        assertThrows(IllegalArgumentException.class, () -> info.setName(tooLongName));
        assertEquals(APP_NAME, info.getName());
    }


    /**
     * Test that name alone (no namespace) at exactly the maximum length is accepted.
     */
    @Test
    public void testNameAloneAtMaxLength() {
        String maxName = "n".repeat(LeaderElectionInformation.MAX_ELECTION_GROUP_NAME_LENGTH);
        assertDoesNotThrow(() -> new LeaderElectionInformation(null, maxName, NODE_1));
    }
}
