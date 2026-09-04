/*
 * SQLServerLeaderElectionTest.java
 *
 * Copyright by toolarium, all rights reserved.
 */
package com.github.toolarium.leader.election.impl.database;

import com.github.toolarium.leader.election.dto.db.DatabaseLeaderElectionConfiguration;
import com.github.toolarium.leader.election.dto.db.SQLServerLeaderElectionDatabaseStructure;
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
     * @see com.github.toolarium.leader.election.impl.database.AbstractDatabaseLeaderElectorTest#legacyTableDDL(String)
     *     SQL Server uses NVARCHAR and requires bracket-quoting for the reserved word {@code timestamp}.
     */
    @Override
    protected String legacyTableDDL(String tableName) {
        return "CREATE TABLE " + tableName
                + "(id NVARCHAR(36) NOT NULL, name NVARCHAR(512) NOT NULL, "
                + "instance NVARCHAR(255), username NVARCHAR(255), [timestamp] BIGINT NOT NULL, "
                + "CONSTRAINT pk_" + tableName.toLowerCase() + " PRIMARY KEY (name))";
    }


    /**
     * @see com.github.toolarium.leader.election.impl.database.AbstractDatabaseLeaderElectorTest#mismatchedTableDDL(String)
     *     SQL Server requires NVARCHAR instead of varchar.
     */
    @Override
    protected String mismatchedTableDDL(String tableName) {
        return "CREATE TABLE " + tableName
                + "(id NVARCHAR(36) NOT NULL, name NVARCHAR(512) NOT NULL, "
                + "junk NVARCHAR(255), CONSTRAINT pk_" + tableName.toLowerCase() + " PRIMARY KEY (name))";
    }


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
