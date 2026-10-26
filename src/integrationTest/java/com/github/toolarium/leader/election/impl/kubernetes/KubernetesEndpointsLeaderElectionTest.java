/*
 * KubernetesEndpointsLeaderElectionTest.java
 *
 * Copyright by toolarium, all rights reserved.
 */
package com.github.toolarium.leader.election.impl.kubernetes;

import com.github.toolarium.leader.election.dto.LeaderElectionConfiguration;
import com.github.toolarium.leader.election.dto.kubernetes.KubernetesLeaderElectionConfiguration;
import com.github.toolarium.leader.election.dto.kubernetes.KubernetesLockType;
import io.kubernetes.client.openapi.apis.CoreV1Api;
import io.kubernetes.client.openapi.models.V1Endpoints;
import java.time.Duration;
import org.junit.jupiter.api.Tag;
import org.testcontainers.junit.jupiter.Testcontainers;


/**
 * Integration test for the Kubernetes leader election strategy with the Endpoints lock of release 1.2.x against a real k3s cluster.
 *
 * @author patrick
 */
@Tag("integration")
@Testcontainers(disabledWithoutDocker = true)
public class KubernetesEndpointsLeaderElectionTest extends KubernetesLeaderElectionTest {
    // annotation in which the Endpoints lock stores the leader election record
    private static final String LEADER_ANNOTATION = "control-plane.alpha.kubernetes.io/leader";


    /**
     * @see com.github.toolarium.leader.election.impl.kubernetes.KubernetesLeaderElectionTest#createConfiguration()
     */
    @Override
    protected LeaderElectionConfiguration createConfiguration() {
        return new KubernetesLeaderElectionConfiguration(KubernetesLockType.ENDPOINTS, Duration.ofSeconds(10), Duration.ofSeconds(7), Duration.ofSeconds(5));
    }


    /**
     * @see com.github.toolarium.leader.election.impl.kubernetes.KubernetesLeaderElectionTest#readLockHolder(java.lang.String)
     */
    @Override
    protected String readLockHolder(String lockName) throws Exception {
        V1Endpoints endpoints = new CoreV1Api(apiClient).readNamespacedEndpoints(lockName, "default").execute();
        String record = endpoints.getMetadata().getAnnotations().get(LEADER_ANNOTATION);
        // the annotation is the JSON leader election record: {"holderIdentity":"...",...}
        int start = record.indexOf("\"holderIdentity\":\"") + "\"holderIdentity\":\"".length();
        return record.substring(start, record.indexOf('"', start));
    }
}
