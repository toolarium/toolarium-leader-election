/*
 * DatabaseLeaderElectionTest.java
 *
 * Copyright by toolarium, all rights reserved.
 */
package com.github.toolarium.leader.election.impl.database;

import com.github.toolarium.leader.election.dto.DatabaseLeaderElectionConfiguration;
import org.h2.jdbcx.JdbcDataSource;


/**
 * Implements leader election tests against an in-memory H2 database.
 *
 * @author patrick
 */
public class DatabaseLeaderElectionTest extends AbstractDatabaseLeaderElectorTest {


    /**
     * @see com.github.toolarium.leader.election.impl.database.AbstractDatabaseLeaderElectorTest#createConfiguration()
     */
    @Override
    protected DatabaseLeaderElectionConfiguration createConfiguration() throws Exception {
        JdbcDataSource ds = new JdbcDataSource();
        ds.setURL("jdbc:h2:mem:leaderelection;DB_CLOSE_DELAY=-1");
        ds.setUser("sa");
        ds.setPassword("");
        DatabaseLeaderElectionConfiguration config = new DatabaseLeaderElectionConfiguration(2);
        config.setDataSource(ds);
        return config;
    }
}
