/*
 * KubernetesLeaderElectotionTest.java
 *
 * Copyright by toolarium, all rights reserved.
 */
package com.github.toolarium.leader.election.impl.kubernetes;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.github.toolarium.leader.election.AbstractLeaderElectorTest;
import com.github.toolarium.leader.election.LeaderElectionFactory;
import com.github.toolarium.leader.election.LeaderElector;
import com.github.toolarium.leader.election.dto.LeaderElectionConfiguration;
import com.github.toolarium.leader.election.dto.LeaderElectionInformation;
import io.kubernetes.client.extended.leaderelection.LeaderElectionConfig;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


/**
 * Test the kubernetes leader election
 *
 * @author patrick
 */
public class KubernetesLeaderElectotionTest extends AbstractLeaderElectorTest {
    private static final Logger LOG = LoggerFactory.getLogger(KubernetesLeaderElectotionTest.class);

    private final AtomicInteger counter = new AtomicInteger(0);


    /**
     * Set up the test
     */
    @BeforeEach
    public void setUp() {
        MockResourceLock.initialize();
    }


    /**
     * Tear down the test — reset singleton state modified during tests.
     */
    @AfterEach
    public void tearDown() {
        KubernetesUtil.getInstance().setCheckEnvironmentVariables(true);
    }


    /**
     * @see com.github.toolarium.leader.election.AbstractLeaderElectorTest#getInitWaitMillis()
     */
    @Override
    protected long getInitWaitMillis() {
        return 3000L;
    }


    /**
     * @see com.github.toolarium.leader.election.AbstractLeaderElectorTest#getFailoverWaitMillis()
     */
    @Override
    protected long getFailoverWaitMillis() {
        return 4000L;
    }


    /**
     * @see com.github.toolarium.leader.election.AbstractLeaderElectorTest#createLeaderElector(java.lang.String)
     */
    @Override
    protected LeaderElector createLeaderElector(String name) throws Exception {
        String identity = name + "-" + counter.incrementAndGet();
        return MockKubernetesLeaderElector.getInstance().getLeaderElection(
                new LeaderElectionInformation("namespace", "name", identity), new LeaderElectionConfiguration(2));
    }


    /**
     * Test the kubernetes
     *
     * @throws Exception In case of an error
     */
    @Test
    public void testKubernetes() throws Exception {

        KubernetesUtil.getInstance().setCheckEnvironmentVariables(false);
        //KubernetesUtil.getInstance().setCheckEndpoint(false);

        LeaderElector el = LeaderElectionFactory.getInstance().getLeaderElection(new LeaderElectionInformation("namespace", "name", "kubernetes-fallback-test"), new LeaderElectionConfiguration(2));
        el.initialize();
        assertFalse(el.isLeader());
        Thread.sleep(getInitWaitMillis());

        assertTrue(el.isLeader());
        Thread.sleep(2000);
        assertTrue(el.isLeader());
        Thread.sleep(2000);
        assertTrue(el.isLeader());
        el.close();
    }


    /**
     * Test the leader election
     *
     * @throws InterruptedException In case of interruption
     */
    @Test
    public void testSimpleLeaderElection() throws InterruptedException {
        List<String> electionHistory = new ArrayList<>();
        List<String> leadershipHistory = new ArrayList<>();

        MockResourceLock mockLock = new MockResourceLock("mock");
        mockLock.setRenewCountMax(3);

        mockLock.setConsumers(
                record -> {
                    electionHistory.add("create record");
                    leadershipHistory.add("get leadership");
                },
                record -> {
                    electionHistory.add("update record");
                },
                record -> {
                    electionHistory.add("change record");
                });

        //LeaderElectionConfiguration(2)
        LeaderElectionConfig leaderElectionConfig = new LeaderElectionConfig();
        leaderElectionConfig.setLock(mockLock);
        leaderElectionConfig.setLeaseDuration(Duration.ofMillis(1000));
        leaderElectionConfig.setRenewDeadline(Duration.ofMillis(750));
        leaderElectionConfig.setRetryPeriod(Duration.ofMillis(500));
        io.kubernetes.client.extended.leaderelection.LeaderElector leaderElector = new io.kubernetes.client.extended.leaderelection.LeaderElector(leaderElectionConfig);

        CountDownLatch testLeaderElectionLatch = new CountDownLatch(2);
        ExecutorService leaderElectionWorker = Executors.newSingleThreadExecutor();
        leaderElectionWorker.submit(() -> {
            leaderElector.run(() -> {
                leadershipHistory.add("start leading");
                testLeaderElectionLatch.countDown();
            }, () -> {
                leadershipHistory.add("stop leading");
                testLeaderElectionLatch.countDown();
            });
        });

        testLeaderElectionLatch.await(10, TimeUnit.SECONDS);

        LOG.debug("=" + electionHistory);
        LOG.debug("=" + leadershipHistory);
        assertHistory(electionHistory, "update record", "update record", "update record");
        assertHistory(leadershipHistory, "start leading", "stop leading");
        leaderElector.close();
    }


    /**
     * Test the leader election with two competing electors
     *
     * @throws InterruptedException In case of interruption
     */
    @Test
    public void testLeaderElection() throws InterruptedException {
        List<String> electionHistory = new ArrayList<>();
        List<String> leadershipHistory = new ArrayList<>();
        CountDownLatch lockAStartLeading = new CountDownLatch(1);

        MockResourceLock mockLockA = new MockResourceLock("mockA");
        mockLockA.setRenewCountMax(3);
        mockLockA.setConsumers(
                record -> {
                    electionHistory.add("A creates record");
                    leadershipHistory.add("A gets leadership");
                },
                record -> {
                    electionHistory.add("A updates record");
                    lockAStartLeading.countDown();
                },
                record -> {
                    leadershipHistory.add("A gets leadership");
                });

        LeaderElectionConfig leaderElectionConfigA = new LeaderElectionConfig();
        leaderElectionConfigA.setLock(mockLockA);
        leaderElectionConfigA.setLeaseDuration(Duration.ofMillis(2000));
        leaderElectionConfigA.setRenewDeadline(Duration.ofMillis(400));
        leaderElectionConfigA.setRetryPeriod(Duration.ofMillis(300));
        final io.kubernetes.client.extended.leaderelection.LeaderElector leaderElectorA = new io.kubernetes.client.extended.leaderelection.LeaderElector(leaderElectionConfigA);

        MockResourceLock mockLockB = new MockResourceLock("mockB");
        mockLockB.setRenewCountMax(4);
        mockLockB.setConsumers(
            record -> {
                electionHistory.add("B creates record");
                leadershipHistory.add("B gets leadership");
            },
            record -> {
                electionHistory.add("B updates record");
            },
            record -> {
                leadershipHistory.add("B gets leadership");
            });
        LeaderElectionConfig leaderElectionConfigB = new LeaderElectionConfig();
        leaderElectionConfigB.setLock(mockLockB);
        leaderElectionConfigB.setLeaseDuration(Duration.ofMillis(2000));
        leaderElectionConfigB.setRenewDeadline(Duration.ofMillis(400));
        leaderElectionConfigB.setRetryPeriod(Duration.ofMillis(300));
        io.kubernetes.client.extended.leaderelection.LeaderElector leaderElectorB = new io.kubernetes.client.extended.leaderelection.LeaderElector(leaderElectionConfigB);

        CountDownLatch testLeaderElectionLatch = new CountDownLatch(4);
        ExecutorService leaderElectionWorker = Executors.newFixedThreadPool(2);
        leaderElectionWorker.submit(() -> {
            leaderElectorA.run(() -> {
                leadershipHistory.add("A starts leading");
                testLeaderElectionLatch.countDown();
            }, () -> {
                leadershipHistory.add("A stops leading");
                testLeaderElectionLatch.countDown();
            });
        });

        lockAStartLeading.await(3, TimeUnit.SECONDS);
        leaderElectionWorker.submit(() -> {
            leaderElectorB.run(() -> {
                leadershipHistory.add("B starts leading");
                testLeaderElectionLatch.countDown();
            }, () -> {
                leadershipHistory.add("B stops leading");
                testLeaderElectionLatch.countDown();
            });
        });

        testLeaderElectionLatch.await(10, TimeUnit.SECONDS);

        assertHistory(electionHistory, "A updates record", "A updates record", "A updates record",
                "B updates record", "B updates record", "B updates record", "B updates record");
        assertHistory(leadershipHistory, "A starts leading", "A stops leading",
                "B gets leadership", "B starts leading", "B stops leading");

        leaderElectorA.close();
        leaderElectorB.close();
    }

    /**
     * Assert history
     *
     * @param history the history
     * @param expected the expected
     */
    private void assertHistory(List<String> history, String... expected) {
        assertNotNull(expected);
        assertNotNull(history);
        assertEquals(expected.length, history.size());

        for (int index = 0; index < history.size(); ++index) {
            assertEquals(expected[index], history.get(index),
                    String.format("Not equal at index %d, expected %s, got %s", index, expected[index], history.get(index)));
        }
    }
}
