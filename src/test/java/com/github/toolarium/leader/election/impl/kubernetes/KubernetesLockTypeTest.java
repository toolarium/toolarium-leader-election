/*
 * KubernetesLockTypeTest.java
 *
 * Copyright by toolarium, all rights reserved.
 */
package com.github.toolarium.leader.election.impl.kubernetes;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.github.toolarium.leader.election.ILeaderElector;
import com.github.toolarium.leader.election.LeaderElectionFactory;
import com.github.toolarium.leader.election.dto.LeaderElectionConfiguration;
import com.github.toolarium.leader.election.dto.LeaderElectionInformation;
import com.github.toolarium.leader.election.dto.kubernetes.KubernetesLeaderElectionConfiguration;
import com.github.toolarium.leader.election.dto.kubernetes.KubernetesLockType;
import io.kubernetes.client.extended.leaderelection.resourcelock.EndpointsLock;
import io.kubernetes.client.extended.leaderelection.resourcelock.LeaseLock;
import io.kubernetes.client.openapi.ApiClient;
import java.time.Duration;
import org.junit.jupiter.api.Test;


/**
 * Tests the selection of the Kubernetes lock resource.
 *
 * @author patrick
 */
public class KubernetesLockTypeTest {
    private static final LeaderElectionInformation INFO = new LeaderElectionInformation("default", "lock-type-test", "node-1");


    /**
     * Without a lock type the elector uses a Lease, also for a plain {@link LeaderElectionConfiguration}.
     */
    @Test
    public void testDefaultIsLease() {
        KubernetesLeaderElectorImpl plain = new KubernetesLeaderElectorImpl(INFO, new LeaderElectionConfiguration(), new ApiClient());
        assertEquals(KubernetesLockType.LEASE, plain.getLockType());
        assertTrue(plain.createLock(INFO) instanceof LeaseLock);

        KubernetesLeaderElectorImpl kubernetes = new KubernetesLeaderElectorImpl(INFO, new KubernetesLeaderElectionConfiguration(), new ApiClient());
        assertTrue(kubernetes.createLock(INFO) instanceof LeaseLock);
    }


    /**
     * The Endpoints lock is used when selected.
     */
    @Test
    public void testEndpointsWhenSelected() {
        KubernetesLeaderElectionConfiguration config = new KubernetesLeaderElectionConfiguration(KubernetesLockType.ENDPOINTS, Duration.ofSeconds(15), Duration.ofSeconds(10), Duration.ofSeconds(5));
        KubernetesLeaderElectorImpl elector = new KubernetesLeaderElectorImpl(INFO, config, new ApiClient());
        assertEquals(KubernetesLockType.ENDPOINTS, elector.getLockType());
        assertTrue(elector.createLock(INFO) instanceof EndpointsLock);
    }


    /**
     * The lock type is validated and part of equals.
     */
    @Test
    public void testConfiguration() {
        KubernetesLeaderElectionConfiguration config = new KubernetesLeaderElectionConfiguration();
        assertEquals(KubernetesLockType.LEASE, config.getLockType());
        assertThrows(IllegalArgumentException.class, () -> config.setLockType(null));

        KubernetesLeaderElectionConfiguration endpoints = new KubernetesLeaderElectionConfiguration();
        endpoints.setLockType(KubernetesLockType.ENDPOINTS);
        assertNotEquals(config, endpoints);
        assertTrue(endpoints.toString().contains("lockType=ENDPOINTS"));
    }


    /**
     * The builder passes the lock type to the elector; the default is a Lease.
     *
     * @throws Exception in case of an error
     */
    @Test
    public void testBuilder() throws Exception {
        ILeaderElector lease = LeaderElectionFactory.builder().namespace("default").name("lock-type-builder").kubernetes().build();
        ILeaderElector endpoints = LeaderElectionFactory.builder().namespace("default").name("lock-type-builder-ep").kubernetes().lockType(KubernetesLockType.ENDPOINTS).build();
        try {
            assertEquals(KubernetesLockType.LEASE, ((KubernetesLeaderElectorImpl) lease).getLockType());
            assertEquals(KubernetesLockType.ENDPOINTS, ((KubernetesLeaderElectorImpl) endpoints).getLockType());
        } finally {
            lease.close();
            endpoints.close();
        }
    }
}
