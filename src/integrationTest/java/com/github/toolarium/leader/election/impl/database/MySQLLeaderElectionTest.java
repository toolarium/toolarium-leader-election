/*
 * MySQLLeaderElectionTest.java
 *
 * Copyright by toolarium, all rights reserved.
 */
package com.github.toolarium.leader.election.impl.database;

import com.github.toolarium.leader.election.dto.db.DatabaseLeaderElectionConfiguration;
import com.github.toolarium.leader.election.dto.db.MySQLLeaderElectionDatabaseStructure;
import com.mysql.cj.jdbc.MysqlDataSource;
import org.junit.jupiter.api.Tag;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;


/**
 * Implements leader election tests against a real MySQL container.
 *
 * @author patrick
 */
@Tag("integration")
@Testcontainers(disabledWithoutDocker = true)
public class MySQLLeaderElectionTest extends AbstractDatabaseLeaderElectorTest {

    @Container
    private static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.0");


    /**
     * @see com.github.toolarium.leader.election.impl.database.AbstractDatabaseLeaderElectorTest#legacyTableDDL(String)
     *     MySQL treats {@code timestamp} as a reserved word requiring backtick quoting.
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
        MysqlDataSource ds = new MysqlDataSource();
        ds.setUrl(MYSQL.getJdbcUrl());
        ds.setUser(MYSQL.getUsername());
        ds.setPassword(MYSQL.getPassword());
        DatabaseLeaderElectionConfiguration config = new DatabaseLeaderElectionConfiguration(2);
        config.setDataSource(ds);
        config.setLeaderElectionDatabaseStructure(new MySQLLeaderElectionDatabaseStructure());
        return config;
    }
}
