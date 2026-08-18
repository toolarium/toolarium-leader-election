/*
 * OracleLeaderElectionTest.java
 *
 * Copyright by toolarium, all rights reserved.
 */
package com.github.toolarium.leader.election.impl.database;

import com.github.toolarium.leader.election.dto.DatabaseLeaderElectionConfiguration;
import com.github.toolarium.leader.election.dto.OracleLeaderElectionDatabaseStructure;
import java.sql.SQLException;
import java.time.Duration;
import oracle.jdbc.pool.OracleDataSource;
import org.junit.jupiter.api.Tag;
import org.testcontainers.containers.OracleContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;


/**
 * Implements leader election tests against a real Oracle container.
 *
 * @author patrick
 */
@Tag("integration")
@Testcontainers(disabledWithoutDocker = true)
public class OracleLeaderElectionTest extends AbstractDatabaseLeaderElectorTest {

    @Container
    private static final OracleContainer ORACLE = new OracleContainer("gvenzl/oracle-xe:21-slim");


    /**
     * @see com.github.toolarium.leader.election.AbstractLeaderElectorTest#getInitWaitMillis()
     *     Oracle XE in Docker has ~1s DML latency; retryPeriod=5s means first tick completes in ~6s.
     */
    @Override
    protected long getInitWaitMillis() {
        return 8000L;
    }


    /**
     * @see com.github.toolarium.leader.election.AbstractLeaderElectorTest#getFailoverWaitMillis()
     *     After graceful close (delete ~1s), B needs one full retryPeriod (5s) + insert (~1s) = ~7s.
     */
    @Override
    protected long getFailoverWaitMillis() {
        return 10000L;
    }


    /**
     * @see com.github.toolarium.leader.election.impl.database.AbstractDatabaseLeaderElectorTest#createConfiguration()
     */
    @Override
    protected DatabaseLeaderElectionConfiguration createConfiguration() throws Exception {
        // Use a generous timeout so Oracle's ~1s-per-DML latency does not cause leadership expiry
        // between renewals. retryPeriod=5s, timeout=30s gives plenty of headroom.
        OracleDataSource ds = new OracleDataSource();
        ds.setURL(ORACLE.getJdbcUrl());
        ds.setUser(ORACLE.getUsername());
        ds.setPassword(ORACLE.getPassword());
        DatabaseLeaderElectionConfiguration config = new DatabaseLeaderElectionConfiguration(
                Duration.ofSeconds(30), Duration.ofSeconds(5), Duration.ofSeconds(5));
        config.setDataSource(ds);
        config.setLeaderElectionDatabaseStructure(new OracleLeaderElectionDatabaseStructure());
        return config;
    }
}
