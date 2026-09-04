/*
 * LeaderElectionInformationTest.java
 *
 * Copyright by toolarium, all rights reserved.
 */
package com.github.toolarium.leader.election.dto;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

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
        assertEquals("", info.getNamespace());
        assertEquals("", info.getName());
        assertEquals(NODE_1, info.getIdentity());
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
     * Test getUniqueName with only identity.
     */
    @Test
    public void testGetUniqueNameIdentityOnly() {
        LeaderElectionInformation info = new LeaderElectionInformation(NODE_1);
        assertEquals(NODE_1, info.getUniqueName());
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
     * Test getElectionGroupName with all fields set — identity is excluded from the key so that
     * all per-node identities compete for the same database row.
     */
    @Test
    public void testGetElectionGroupNameAllFields() {
        LeaderElectionInformation info = new LeaderElectionInformation(NAMESPACE, APP_NAME, NODE_1);
        assertEquals("ns.myapp", info.getElectionGroupName());
    }


    /**
     * Test getElectionGroupName with identity only — falls back to identity so that the
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
        assertEquals("LeaderElectionInformation [namespace=ns, name=myapp, identity=node-1]", info.toString());
    }
}
