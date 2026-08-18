/*
 * AbstractDatabaseLeaderElectorTest.java
 *
 * Copyright by toolarium, all rights reserved.
 */
package com.github.toolarium.leader.election.impl.database;

import com.github.toolarium.leader.election.AbstractLeaderElectorTest;
import com.github.toolarium.leader.election.LeaderElectionFactory;
import com.github.toolarium.leader.election.LeaderElectionStrategy;
import com.github.toolarium.leader.election.LeaderElector;
import com.github.toolarium.leader.election.dto.DatabaseLeaderElectionConfiguration;
import com.github.toolarium.leader.election.dto.LeaderElectionInformation;


/**
 * Abstract base test for database-backed leader election.
 * Subclasses implement {@link #createConfiguration()} to supply the JDBC connection
 * and database structure; all test logic is inherited from {@link AbstractLeaderElectorTest}.
 *
 * @author patrick
 */
public abstract class AbstractDatabaseLeaderElectorTest extends AbstractLeaderElectorTest {


    /**
     * Create a fully configured {@link DatabaseLeaderElectionConfiguration} for this backend.
     *
     * @return the database leader election configuration
     * @throws Exception In case of an error
     */
    protected abstract DatabaseLeaderElectionConfiguration createConfiguration() throws Exception;


    /**
     * @see com.github.toolarium.leader.election.AbstractLeaderElectorTest#createLeaderElector(java.lang.String)
     */
    @Override
    protected LeaderElector createLeaderElector(String name) throws Exception {
        return LeaderElectionFactory.getInstance().getLeaderElection(
                LeaderElectionStrategy.DATABASE, new LeaderElectionInformation(name), createConfiguration());
    }
}
