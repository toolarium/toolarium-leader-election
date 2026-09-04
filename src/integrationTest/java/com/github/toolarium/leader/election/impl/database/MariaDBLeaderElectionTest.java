/*
 * MariaDBLeaderElectionTest.java
 *
 * Copyright by toolarium, all rights reserved.
 */
package com.github.toolarium.leader.election.impl.database;

import com.github.toolarium.leader.election.dto.db.DatabaseLeaderElectionConfiguration;
import com.github.toolarium.leader.election.dto.db.MariaDBLeaderElectionDatabaseStructure;
import org.junit.jupiter.api.Tag;
import org.mariadb.jdbc.MariaDbDataSource;
import org.testcontainers.containers.MariaDBContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;


/**
 * Implements leader election tests against a real MariaDB container.
 *
 * @author patrick
 */
@Tag("integration")
@Testcontainers(disabledWithoutDocker = true)
public class MariaDBLeaderElectionTest extends AbstractDatabaseLeaderElectorTest {

    @Container
    private static final MariaDBContainer<?> MARIADB = new MariaDBContainer<>("mariadb:11");


    /**
     * @see com.github.toolarium.leader.election.impl.database.AbstractDatabaseLeaderElectorTest#legacyTableDDL(String)
     *     MariaDB treats {@code timestamp} as a reserved word requiring backtick quoting.
     *     ROW_FORMAT=DYNAMIC is needed for the varchar(512) primary key.
     */
    @Override
    protected String legacyTableDDL(String tableName) {
        return "CREATE TABLE " + tableName
                + "(id varchar(36) NOT NULL, name varchar(512) NOT NULL, "
                + "instance varchar(255), username varchar(255), `timestamp` bigint NOT NULL, "
                + "CONSTRAINT pk_" + tableName.toLowerCase() + " PRIMARY KEY (name)) ROW_FORMAT=DYNAMIC";
    }


    /**
     * @see com.github.toolarium.leader.election.impl.database.AbstractDatabaseLeaderElectorTest#createConfiguration()
     */
    @Override
    protected DatabaseLeaderElectionConfiguration createConfiguration() throws Exception {
        MariaDbDataSource ds = new MariaDbDataSource(MARIADB.getJdbcUrl());
        ds.setUser(MARIADB.getUsername());
        ds.setPassword(MARIADB.getPassword());
        DatabaseLeaderElectionConfiguration config = new DatabaseLeaderElectionConfiguration(2);
        config.setDataSource(ds);
        config.setLeaderElectionDatabaseStructure(new MariaDBLeaderElectionDatabaseStructure());
        return config;
    }
}
