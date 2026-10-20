/*
 * LeaderElectionFactoryTest.java
 *
 * Copyright by toolarium, all rights reserved.
 */
package com.github.toolarium.leader.election;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.github.toolarium.leader.election.dto.ILeaderElectionConfiguration;
import com.github.toolarium.leader.election.dto.LeaderElectionConfiguration;
import com.github.toolarium.leader.election.dto.LeaderElectionInformation;
import com.github.toolarium.leader.election.dto.LeaderElectionStrategy;
import com.github.toolarium.leader.election.dto.db.DatabaseLeaderElectionConfiguration;
import com.github.toolarium.leader.election.dto.db.JDBCLeaderElectionDatabaseStructure;
import com.github.toolarium.leader.election.exception.LeaderElectionException;
import com.github.toolarium.leader.election.impl.kubernetes.KubernetesUtil;
import com.github.toolarium.leader.election.impl.kubernetes.MockKubernetesLeaderElector;
import com.github.toolarium.leader.election.impl.kubernetes.MockResourceLock;
import java.time.Duration;
import java.util.function.BooleanSupplier;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Test
 *
 * @author patrick
 */
public class LeaderElectionFactoryTest {
    private static final Logger LOG = LoggerFactory.getLogger(LeaderElectionFactoryTest.class);
    private static final String BUILDER_NAMESPACE = "ns";
    private static final String BUILDER_IDENTITY = "node-1";


    /**
     * Tear down the test — reset singleton state modified during tests.
     */
    @AfterEach
    public void tearDown() {
        KubernetesUtil.getInstance().setCheckEnvironmentVariables(true);
        KubernetesUtil.getInstance().setCheckEndpoint(true);
    }


    /**
     * Test
     *
     * @throws Exception In case of an error
     */
    @Test
    public void test() throws Exception {
        verifyElect(LeaderElectionStrategy.FILE, new LeaderElectionInformation("testFile"), new LeaderElectionConfiguration(2));
        testMultipleLeaders(LeaderElectionStrategy.FILE, new LeaderElectionInformation("testFile"), new LeaderElectionConfiguration(2));

        verifyElect(LeaderElectionStrategy.NETWORK, new LeaderElectionInformation("testNetworkVerify"), new LeaderElectionConfiguration(2));
        testMultipleLeaders(LeaderElectionStrategy.NETWORK, new LeaderElectionInformation("testNetworkMulti"), new LeaderElectionConfiguration(2));

        JdbcDataSource ds = new JdbcDataSource();
        ds.setURL("jdbc:h2:mem:factorytest;DB_CLOSE_DELAY=-1");
        ds.setUser("sa");
        ds.setPassword("");
        DatabaseLeaderElectionConfiguration config = new DatabaseLeaderElectionConfiguration(2).setDataSource(ds);
        verifyElect(LeaderElectionStrategy.DATABASE, new LeaderElectionInformation("testDatabase"), config);
        testMultipleLeaders(LeaderElectionStrategy.DATABASE, new LeaderElectionInformation("testDatabase"), config);
    }

    /**
     * Test the file strategy
     *
     * @throws Exception In case of an error
     */
    @Test
    public void testFile() throws Exception {
        ILeaderElector leaderElectorA = LeaderElectionFactory.getInstance().getLeaderElection(LeaderElectionStrategy.FILE, new LeaderElectionInformation("testFile"), new LeaderElectionConfiguration(2));
        assertFalse(leaderElectorA.isLeader());
        Thread.sleep(50);
        assertFalse(leaderElectorA.isLeader());
        assertTrue(leaderElectorA.elect(Duration.ofMillis(2000)));
        assertTrue(leaderElectorA.isLeader());
        leaderElectorA.close();
        assertFalse(leaderElectorA.isLeader());
    }

    /**
     * Test the network strategy
     *
     * @throws Exception In case of an error
     */
    @Test
    public void testNetwork() throws Exception {
        ILeaderElector leaderElectorA = LeaderElectionFactory.getInstance().getLeaderElection(LeaderElectionStrategy.NETWORK, new LeaderElectionInformation("testNetwork"), new LeaderElectionConfiguration(2));
        assertFalse(leaderElectorA.isLeader());
        Thread.sleep(50);
        assertFalse(leaderElectorA.isLeader());
        assertTrue(leaderElectorA.elect(Duration.ofMillis(15000)));
        assertTrue(leaderElectorA.isLeader());
        leaderElectorA.close();
        assertFalse(leaderElectorA.isLeader());
    }


    /**
     * Test the database strategy with an H2 in-memory datasource.
     * Shows the minimum setup required: create a {@link DatabaseLeaderElectionConfiguration},
     * attach a {@link javax.sql.DataSource}, and use it exactly like any other strategy.
     *
     * @throws Exception In case of an error
     */
    @Test
    public void testDatabase() throws Exception {
        JdbcDataSource ds = new JdbcDataSource();
        ds.setURL("jdbc:h2:mem:factorytest;DB_CLOSE_DELAY=-1");
        ds.setUser("sa");
        ds.setPassword("");

        DatabaseLeaderElectionConfiguration config = new DatabaseLeaderElectionConfiguration(2);
        config.setDataSource(ds);

        ILeaderElector leaderElectorA = LeaderElectionFactory.getInstance().getLeaderElection(LeaderElectionStrategy.DATABASE, new LeaderElectionInformation("testDatabase"), config);
        assertFalse(leaderElectorA.isLeader());
        Thread.sleep(50);
        assertFalse(leaderElectorA.isLeader());
        assertTrue(leaderElectorA.elect(Duration.ofMillis(2000)));
        assertTrue(leaderElectorA.isLeader());
        leaderElectorA.close();
        assertFalse(leaderElectorA.isLeader());
    }


    /**
     * Test the kubernetes strategy using the builder and mock elector.
     *
     * @throws Exception In case of an error
     */
    @Test
    public void testKubernetes() throws Exception {
        KubernetesUtil.getInstance().setCheckEnvironmentVariables(false);
        MockResourceLock.initialize();

        ILeaderElector el = MockKubernetesLeaderElector.builder()
                .namespace("namespace").name("name").identity("test")
                .timeout(Duration.ofSeconds(2))
                .build();
        assertTrue(el.elect(Duration.ofMillis(3000)));

        assertTrue(el.isLeader());
        Thread.sleep(500);
        assertTrue(el.isLeader());
        Thread.sleep(500);
        assertTrue(el.isLeader());
        el.close();
    }


    /**
     * Test that DATABASE strategy with a non-DatabaseLeaderElectionConfiguration throws LeaderElectionException.
     */
    @Test
    public void testDatabaseStrategyRequiresDatabaseConfiguration() {
        assertThrows(LeaderElectionException.class, () ->
            LeaderElectionFactory.getInstance().getLeaderElection(LeaderElectionStrategy.DATABASE, new LeaderElectionInformation("testDb"), new LeaderElectionConfiguration(2)));
    }


    /**
     * Test that passing null as strategy throws LeaderElectionException.
     */
    @Test
    public void testNullStrategyThrows() {
        assertThrows(LeaderElectionException.class, () ->
            LeaderElectionFactory.getInstance().getLeaderElection(
                null, new LeaderElectionInformation("test"), new LeaderElectionConfiguration(2)));
    }


    /**
     * Test the FILE strategy builder (default basePath).
     *
     * @throws Exception In case of an error
     */
    @Test
    public void testFileBuilder() throws Exception {
        ILeaderElector elector = LeaderElectionFactory.builder()
            .namespace(BUILDER_NAMESPACE).name("file-builder").identity(BUILDER_IDENTITY)
            .timeout(Duration.ofSeconds(2))
            .file()
            .build();
        assertTrue(elector.elect(Duration.ofMillis(2000)));
        assertTrue(elector.isLeader());
        elector.close();
        assertFalse(elector.isLeader());
    }


    /**
     * Test the FILE strategy builder with a custom basePath.
     *
     * @throws Exception In case of an error
     */
    @Test
    public void testFileBuilderWithCustomBasePath() throws Exception {
        ILeaderElector elector = LeaderElectionFactory.builder()
            .namespace(BUILDER_NAMESPACE).name("file-custom").identity(BUILDER_IDENTITY)
            .timeout(Duration.ofSeconds(2))
            .file()
            .basePath(System.getProperty("java.io.tmpdir"))
            .build();
        assertTrue(elector.elect(Duration.ofMillis(2000)));
        assertTrue(elector.isLeader());
        elector.close();
        assertFalse(elector.isLeader());
    }


    /**
     * Test the NETWORK strategy builder.
     *
     * @throws Exception In case of an error
     */
    @Test
    public void testNetworkBuilder() throws Exception {
        ILeaderElector elector = LeaderElectionFactory.builder()
            .namespace(BUILDER_NAMESPACE).name("network-builder").identity(BUILDER_IDENTITY)
            .timeout(Duration.ofSeconds(2))
            .network()
            .build();
        assertTrue(elector.elect(Duration.ofMillis(15000)));
        assertTrue(elector.isLeader());
        elector.close();
        assertFalse(elector.isLeader());
    }


    /**
     * Test the DATABASE strategy builder (auto-detected structure).
     *
     * @throws Exception In case of an error
     */
    @Test
    public void testDatabaseBuilder() throws Exception {
        JdbcDataSource ds = new JdbcDataSource();
        ds.setURL("jdbc:h2:mem:factorybuilder;DB_CLOSE_DELAY=-1");
        ds.setUser("sa");
        ds.setPassword("");

        ILeaderElector elector = LeaderElectionFactory.builder()
            .namespace(BUILDER_NAMESPACE).name("db-builder").identity(BUILDER_IDENTITY)
            .timeout(Duration.ofSeconds(2))
            .database()
            .dataSource(ds)
            .build();
        assertTrue(elector.elect(Duration.ofMillis(2000)));
        assertTrue(elector.isLeader());
        elector.close();
        assertFalse(elector.isLeader());
    }


    /**
     * Test the DATABASE strategy builder with an explicit databaseStructure.
     *
     * @throws Exception In case of an error
     */
    @Test
    public void testDatabaseBuilderWithExplicitStructure() throws Exception {
        JdbcDataSource ds = new JdbcDataSource();
        ds.setURL("jdbc:h2:mem:factorybuilderstruct;DB_CLOSE_DELAY=-1");
        ds.setUser("sa");
        ds.setPassword("");

        ILeaderElector elector = LeaderElectionFactory.builder()
            .namespace(BUILDER_NAMESPACE).name("db-struct").identity(BUILDER_IDENTITY)
            .timeout(Duration.ofSeconds(2))
            .database()
            .dataSource(ds)
            .databaseStructure(new JDBCLeaderElectionDatabaseStructure())
            .build();
        assertTrue(elector.elect(Duration.ofMillis(2000)));
        assertTrue(elector.isLeader());
        elector.close();
        assertFalse(elector.isLeader());
    }


    /**
     * builder().namespace(null) must treat null as an empty string so the resulting
     * LeaderElectionInformation reports namespace as "".
     */
    @Test
    public void testBuilderNullNamespaceBecomesEmpty() {
        LeaderElectionInformation info = LeaderElectionFactory.builder().namespace(null).buildInfo();
        assertEquals("", info.getNamespace(), "null namespace must be stored as empty string");
    }


    /**
     * builder().name(null) must treat null as an empty string so the resulting
     * LeaderElectionInformation reports name as "".
     */
    @Test
    public void testBuilderNullNameBecomesEmpty() {
        LeaderElectionInformation info = LeaderElectionFactory.builder().name(null).buildInfo();
        assertEquals("", info.getName(), "null name must be stored as empty string");
    }


    /**
     * builder().identity(null) must treat null as an empty string so the resulting
     * LeaderElectionInformation reports identity as "".
     */
    @Test
    public void testBuilderNullIdentityBecomesEmpty() {
        LeaderElectionInformation info = LeaderElectionFactory.builder().identity(null).buildInfo();
        assertEquals("", info.getIdentity(), "null identity must be stored as empty string");
    }


    /**
     * Timing values set on the builder (timeout, renewDeadline, retryPeriod) must be propagated
     * to the built elector's configuration.
     *
     * @throws Exception In case of an error
     */
    @Test
    public void testBuilderTimingsPropagateToFile() throws Exception {
        Duration timeoutDur = Duration.ofSeconds(10);
        Duration renewDur = Duration.ofSeconds(6);
        Duration retryDur = Duration.ofSeconds(3);
        ILeaderElector elector = LeaderElectionFactory.builder()
                .namespace(BUILDER_NAMESPACE).name("timing-test").identity(BUILDER_IDENTITY)
                .timeout(timeoutDur)
                .renewDeadline(renewDur)
                .retryPeriod(retryDur)
                .file()
                .build();
        try {
            ILeaderElectionConfiguration cfg = ((ILeaderElectorView) elector).getLeaderElectionConfiguration();
            assertEquals(timeoutDur, cfg.getTimeout(), "timeout must match the builder value");
            assertEquals(renewDur, cfg.getRenewDeadline(), "renewDeadline must match the builder value");
            assertEquals(retryDur, cfg.getRetryPeriod(), "retryPeriod must match the builder value");
        } finally {
            elector.close();
        }
    }


    /**
     * closeTimeout set on the builder must be propagated to the built elector's configuration.
     *
     * @throws Exception In case of an error
     */
    @Test
    public void testBuilderCloseTimeoutPropagates() throws Exception {
        Duration closeTimeoutDur = Duration.ofSeconds(7);
        ILeaderElector elector = LeaderElectionFactory.builder()
                .namespace(BUILDER_NAMESPACE).name("close-timeout-test").identity(BUILDER_IDENTITY)
                .closeTimeout(closeTimeoutDur)
                .file()
                .build();
        try {
            ILeaderElectionConfiguration cfg = ((ILeaderElectorView) elector).getLeaderElectionConfiguration();
            assertEquals(closeTimeoutDur, cfg.getCloseTimeout(), "closeTimeout must match the builder value");
        } finally {
            elector.close();
        }
    }


    /**
     * Verify simple leader election
     *
     * @param strategy the leader election strategy
     * @param leaderElectionInformation the leader election information
     * @param leaderElectionConfiguration the leader election configuration
     * @throws Exception In case of an error
     */
    protected void verifyElect(LeaderElectionStrategy strategy, LeaderElectionInformation leaderElectionInformation, LeaderElectionConfiguration leaderElectionConfiguration) throws Exception {
        LOG.debug("->Get leader");
        ILeaderElector leaderElector = LeaderElectionFactory.getInstance().getLeaderElection(strategy, leaderElectionInformation, leaderElectionConfiguration);
        LOG.debug("->Check leader");
        assertFalse(leaderElector.isLeader());
        Thread.sleep(50);
        LOG.debug("->Check leader");
        assertFalse(leaderElector.isLeader());
        LOG.debug("->Elect leader");
        assertTrue(leaderElector.elect(Duration.ofMillis(8000)));
        LOG.debug("->Elect leader (idempotent)");
        assertTrue(leaderElector.elect(Duration.ofMillis(100)));
        LOG.debug("->Check leader");
        assertTrue(leaderElector.isLeader());
        LOG.debug("->Close leader");
        leaderElector.close();
        LOG.debug("->Close leader");
        leaderElector.close();
        LOG.debug("->Check leader");
        assertFalse(leaderElector.isLeader());
    }


    /**
     * Test multiple leaders
     *
     * @param strategy the leader election strategy
     * @param leaderElectionInformation the leader election information
     * @param leaderElectionConfiguration the leader election configuration
     * @throws Exception In case of an error
     */
    protected void testMultipleLeaders(LeaderElectionStrategy strategy, LeaderElectionInformation leaderElectionInformation, LeaderElectionConfiguration leaderElectionConfiguration) throws Exception {
        ILeaderElector leaderElectorA = LeaderElectionFactory.getInstance().getLeaderElection(strategy, leaderElectionInformation, leaderElectionConfiguration);
        assertTrue(leaderElectorA.elect(Duration.ofMillis(8000)));
        assertTrue(leaderElectorA.isLeader());

        // create second instance
        ILeaderElector leaderElectorB = LeaderElectionFactory.getInstance().getLeaderElection(strategy, leaderElectionInformation, leaderElectionConfiguration);
        assertFalse(leaderElectorB.elect(Duration.ofMillis(8000)));

        assertTrue(leaderElectorA.isLeader());
        assertFalse(leaderElectorB.isLeader());

        Thread.sleep(500);
        assertTrue(leaderElectorA.isLeader());
        assertFalse(leaderElectorB.isLeader());
        Thread.sleep(500);
        assertTrue(leaderElectorA.isLeader());
        assertFalse(leaderElectorB.isLeader());

        leaderElectorA.close();
        Thread.sleep(200);
        assertFalse(leaderElectorA.isLeader());
        awaitCondition(() -> leaderElectorB.isLeader(), 10000);
        assertTrue(leaderElectorB.isLeader());

        Thread.sleep(50);
        assertTrue(leaderElectorB.isLeader());
        leaderElectorB.close();
    }


    /**
     * Poll the given condition every 50 ms until it returns {@code true} or the timeout elapses.
     * The caller is responsible for asserting the final state after this method returns.
     *
     * @param condition the condition to poll
     * @param timeoutMillis maximum time to wait in milliseconds
     * @throws InterruptedException if the polling sleep is interrupted
     */
    private void awaitCondition(BooleanSupplier condition, long timeoutMillis) throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMillis;
        while (!condition.getAsBoolean() && System.currentTimeMillis() < deadline) {
            Thread.sleep(50L);
        }
    }
}
