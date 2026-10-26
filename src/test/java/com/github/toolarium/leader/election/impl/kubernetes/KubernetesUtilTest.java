/*
 * KubernetesUtilTest.java
 *
 * Copyright by toolarium, all rights reserved.
 */
package com.github.toolarium.leader.election.impl.kubernetes;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

import com.github.toolarium.leader.election.dto.LeaderElectionInformation;
import org.junit.jupiter.api.Test;


/**
 * Tests the detection of a Kubernetes environment outside of Kubernetes.
 *
 * @author patrick
 */
public class KubernetesUtilTest {

    /**
     * Outside of Kubernetes the environment variables are not found, the endpoint check decides when the environment is not checked,
     * and without checks Kubernetes counts as available.
     */
    @Test
    public void testAvailability() {
        KubernetesUtil util = KubernetesUtil.getInstance();
        assumeFalse(util.hasKubernetesEnvironmentVariables(), "The test runs outside of Kubernetes only");
        LeaderElectionInformation info = new LeaderElectionInformation("default", "kubernetes-util-test", "node");
        try {
            assertFalse(util.isAvailable(info), "Without the environment variables Kubernetes is not available");

            // the result of the endpoint check depends on the machine (e.g. a local cluster), so only the consistency is checked
            util.setCheckEnvironmentVariables(false);
            assertEquals(util.hasEndpoint(info), util.isAvailable(info), "Without the environment check the endpoint decides");

            util.setCheckEndpoint(false);
            assertTrue(util.isAvailable(info), "Without any check Kubernetes counts as available");
        } finally {
            util.setCheckEnvironmentVariables(true);
            util.setCheckEndpoint(true);
        }
    }
}
