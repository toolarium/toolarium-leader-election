/*
 * SQLServerLeaderElectionTest.java
 *
 * Copyright by toolarium, all rights reserved.
 */
package com.github.toolarium.leader.election.impl.database;

import com.github.toolarium.leader.election.dto.DatabaseLeaderElectionConfiguration;
import com.github.toolarium.leader.election.dto.SQLServerLeaderElectionDatabaseStructure;
import com.microsoft.sqlserver.jdbc.SQLServerDataSource;
import org.junit.jupiter.api.Tag;
import org.testcontainers.containers.MSSQLServerContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;


/**
 * Implements leader election tests against a real SQL Server container.
 *
 * @author patrick
 */
@Tag("integration")
@Testcontainers(disabledWithoutDocker = true)
public class SQLServerLeaderElectionTest extends AbstractDatabaseLeaderElectorTest {

    @Container
    private static final MSSQLServerContainer<?> SQLSERVER =
            new MSSQLServerContainer<>("mcr.microsoft.com/mssql/server:2022-latest").acceptLicense();


    /**
     * @see com.github.toolarium.leader.election.impl.database.AbstractDatabaseLeaderElectorTest#createConfiguration()
     */
    @Override
    protected DatabaseLeaderElectionConfiguration createConfiguration() throws Exception {
        SQLServerDataSource ds = new SQLServerDataSource();
        ds.setURL(SQLSERVER.getJdbcUrl());
        ds.setUser(SQLSERVER.getUsername());
        ds.setPassword(SQLSERVER.getPassword());
        DatabaseLeaderElectionConfiguration config = new DatabaseLeaderElectionConfiguration(2);
        config.setDataSource(ds);
        config.setLeaderElectionDatabaseStructure(new SQLServerLeaderElectionDatabaseStructure());
        return config;
    }
}
