/*
 * KubernetesLeaderElectionTest.java
 *
 * Copyright by toolarium, all rights reserved.
 */
package com.github.toolarium.leader.election.impl.kubernetes;

import com.github.toolarium.leader.election.AbstractLeaderElectorTest;
import com.github.toolarium.leader.election.LeaderElector;
import com.github.toolarium.leader.election.dto.LeaderElectionConfiguration;
import com.github.toolarium.leader.election.dto.LeaderElectionInformation;
import io.kubernetes.client.openapi.ApiClient;
import io.kubernetes.client.util.ClientBuilder;
import io.kubernetes.client.util.KubeConfig;
import java.io.StringReader;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.testcontainers.k3s.K3sContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;


/**
 * Integration test for the Kubernetes leader election strategy against a real k3s cluster.
 *
 * @author patrick
 */
@Tag("integration")
@Testcontainers(disabledWithoutDocker = true)
public class KubernetesLeaderElectionTest extends AbstractLeaderElectorTest {

    @Container
    private static final K3sContainer K3S = new K3sContainer(DockerImageName.parse("rancher/k3s:v1.31.4-k3s1"));

    private static ApiClient apiClient;
    private final AtomicInteger counter = new AtomicInteger(0);


    /**
     * Configure the Kubernetes API client from the k3s kubeconfig once the container is running.
     *
     * @throws Exception in case of error
     */
    @BeforeAll
    static void setupApiClient() throws Exception {
        KubeConfig kubeConfig = KubeConfig.loadKubeConfig(new StringReader(K3S.getKubeConfigYaml()));
        apiClient = ClientBuilder.kubeconfig(kubeConfig).build();
    }


    /**
     * @see com.github.toolarium.leader.election.AbstractLeaderElectorTest#createLeaderElector(java.lang.String)
     */
    @Override
    protected LeaderElector createLeaderElector(String name) throws Exception {
        // Kubernetes resource names must be lowercase RFC 1123: convert camelCase to kebab-case
        String lockName = name.replaceAll("([A-Z])", "-$1").toLowerCase().replaceAll("^-", "");
        String identity = lockName + "-" + counter.incrementAndGet();
        return new KubernetesLeaderElectorImpl(
                new LeaderElectionInformation("default", lockName, identity),
                new LeaderElectionConfiguration(Duration.ofSeconds(10), Duration.ofSeconds(7), Duration.ofSeconds(5)),
                apiClient);
    }


    /**
     * @see com.github.toolarium.leader.election.AbstractLeaderElectorTest#getInitWaitMillis()
     */
    @Override
    protected long getInitWaitMillis() {
        return 12000L;
    }


    /**
     * @see com.github.toolarium.leader.election.AbstractLeaderElectorTest#getFailoverWaitMillis()
     */
    @Override
    protected long getFailoverWaitMillis() {
        return 20000L;
    }
}
