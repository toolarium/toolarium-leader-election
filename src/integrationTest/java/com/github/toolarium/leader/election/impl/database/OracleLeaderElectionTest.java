/*
 * OracleLeaderElectionTest.java
 *
 * Copyright by toolarium, all rights reserved.
 */
package com.github.toolarium.leader.election.impl.database;

import com.github.toolarium.leader.election.dto.db.DatabaseLeaderElectionConfiguration;
import com.github.toolarium.leader.election.dto.db.OracleLeaderElectionDatabaseStructure;
import com.github.toolarium.leader.election.impl.database.dao.LeaderElectionDAO;
import java.sql.SQLException;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicBoolean;
import oracle.jdbc.pool.OracleDataSource;
import org.junit.jupiter.api.BeforeEach;
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
    private static final AtomicBoolean WARMED_UP = new AtomicBoolean(false);


    /**
     * Warm up the freshly started container once: the first statements of a cold Oracle XE take several seconds (table
     * creation, first queries), longer than the query timeout of an election round, so the first test would fail its
     * first round.
     *
     * @throws Exception in case of an error
     */
    @BeforeEach
    public void warmUp() throws Exception {
        if (WARMED_UP.compareAndSet(false, true)) {
            LeaderElectionDAO dao = new LeaderElectionDAO(createConfiguration());
            dao.init();
            dao.selectLeaderByName("warm-up");
            dao.getDatabaseCurrentTimeMillis();
        }
    }


    /**
     * @see com.github.toolarium.leader.election.AbstractLeaderElectorTest#getInitWaitMillis()
     *     Oracle XE in Docker has cold-start JDBC connection overhead plus ~1s DML latency;
     *     retryPeriod=5s means the first tick completes in ~6s after the connection is ready.
     */
    @Override
    protected long getInitWaitMillis() {
        return 15000L;
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
     * @see com.github.toolarium.leader.election.impl.database.AbstractDatabaseLeaderElectorTest#legacyTableDDL(String)
     *     Oracle uses VARCHAR2 and NUMBER(19). The {@code timestamp} column is left unquoted so
     *     Oracle stores it as uppercase {@code TIMESTAMP}, which matches the rename SQL that also
     *     uses uppercase {@code TIMESTAMP}.
     */
    @Override
    protected String legacyTableDDL(String tableName) {
        String base = tableName.toLowerCase();
        String suffix = base.length() > 27 ? base.substring(0, 27) : base;
        return "CREATE TABLE " + tableName
                + "(id VARCHAR2(36) NOT NULL, name VARCHAR2(512) NOT NULL, "
                + "instance VARCHAR2(255), username VARCHAR2(255), timestamp NUMBER(19) NOT NULL, "
                + "CONSTRAINT pk_" + suffix + " PRIMARY KEY (name))";
    }


    /**
     * @see com.github.toolarium.leader.election.impl.database.AbstractDatabaseLeaderElectorTest#mismatchedTableDDL(String)
     *     Oracle requires VARCHAR2 instead of varchar.
     */
    @Override
    protected String mismatchedTableDDL(String tableName) {
        String base = tableName.toLowerCase();
        String suffix = base.length() > 27 ? base.substring(0, 27) : base;
        return "CREATE TABLE " + tableName
                + "(id VARCHAR2(36) NOT NULL, name VARCHAR2(512) NOT NULL, "
                + "junk VARCHAR2(255), CONSTRAINT pk_" + suffix + " PRIMARY KEY (name))";
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
                Duration.ofSeconds(30), Duration.ofSeconds(10), Duration.ofSeconds(5));
        config.setDataSource(ds);
        config.setLeaderElectionDatabaseStructure(new OracleLeaderElectionDatabaseStructure());
        return config;
    }
}
