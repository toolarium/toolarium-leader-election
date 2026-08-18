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

    /**
     * Test full constructor and getters.
     */
    @Test
    public void testFullConstructor() {
        LeaderElectionInformation info = new LeaderElectionInformation("ns", "myapp", "node-1");
        assertEquals("ns", info.getNamespace());
        assertEquals("myapp", info.getName());
        assertEquals("node-1", info.getIdentity());
    }


    /**
     * Test identity-only constructor sets empty namespace and name.
     */
    @Test
    public void testIdentityOnlyConstructor() {
        LeaderElectionInformation info = new LeaderElectionInformation("node-1");
        assertEquals("", info.getNamespace());
        assertEquals("", info.getName());
        assertEquals("node-1", info.getIdentity());
    }


    /**
     * Test getUniqueName with all fields set.
     */
    @Test
    public void testGetUniqueNameAllFields() {
        LeaderElectionInformation info = new LeaderElectionInformation("ns", "myapp", "node-1");
        assertEquals("ns.myapp.node-1", info.getUniqueName());
    }


    /**
     * Test getUniqueName with only identity.
     */
    @Test
    public void testGetUniqueNameIdentityOnly() {
        LeaderElectionInformation info = new LeaderElectionInformation("node-1");
        assertEquals("node-1", info.getUniqueName());
    }


    /**
     * Test getUniqueName with namespace and identity only (empty name).
     */
    @Test
    public void testGetUniqueNameNamespaceAndIdentity() {
        LeaderElectionInformation info = new LeaderElectionInformation("ns", "", "node-1");
        assertEquals("ns.node-1", info.getUniqueName());
    }


    /**
     * Test getUniqueName with name and identity only (empty namespace).
     */
    @Test
    public void testGetUniqueNameNameAndIdentity() {
        LeaderElectionInformation info = new LeaderElectionInformation("", "myapp", "node-1");
        assertEquals("myapp.node-1", info.getUniqueName());
    }


    /**
     * Test setters.
     */
    @Test
    public void testSetters() {
        LeaderElectionInformation info = new LeaderElectionInformation("ns", "myapp", "node-1");
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
        LeaderElectionInformation a = new LeaderElectionInformation("ns", "myapp", "node-1");
        LeaderElectionInformation b = new LeaderElectionInformation("ns", "myapp", "node-1");
        LeaderElectionInformation c = new LeaderElectionInformation("ns", "myapp", "node-2");

        assertEquals(a, b);
        assertEquals(a.hashCode(), b.hashCode());
        assertNotEquals(a, c);
        assertEquals(a, a);
        assertNotEquals(a, null);
        assertNotEquals(a, "other");
    }


    /**
     * Test toString.
     */
    @Test
    public void testToString() {
        LeaderElectionInformation info = new LeaderElectionInformation("ns", "myapp", "node-1");
        assertEquals("LeaderElectionInformation [namespace=ns, name=myapp, identity=node-1]", info.toString());
    }
}
