/*
 * LeaderElectionDAOTest.java
 *
 * Copyright by toolarium, all rights reserved.
 */
package com.github.toolarium.leader.election.impl.database.dao;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import com.github.toolarium.leader.election.dto.DatabaseLeaderElectionConfiguration;
import java.sql.SQLException;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


/**
 * Test the leader election dao.
 * 
 * @author patrick
 */
public class LeaderElectionDAOTest {
    private static final String MYTEST = "mytest";
    private static final Logger LOG = LoggerFactory.getLogger(LeaderElectionDAOTest.class);

    
    /**
     * Test
     *
     * @throws SQLException In case of a database error
     */
    @Test
    public void test() throws SQLException {
        
        JdbcDataSource ds = new JdbcDataSource();
        ds.setURL("jdbc:h2:mem:leaderelectiondao;DB_CLOSE_DELAY=-1");
        ds.setUser("sa");
        ds.setPassword("");
        DatabaseLeaderElectionConfiguration databaseLeaderElectionConfiguration = new DatabaseLeaderElectionConfiguration(2);
        databaseLeaderElectionConfiguration.setDataSource(ds);
        //LeaderElectionDatabaseStructure leaderElectionDatabaseStructure = databaseLeaderElectionConfiguration.getLeaderElectionDatabaseStructure();
        //leaderElectionDatabaseStructure.setTableName("TEST." + leaderElectionDatabaseStructure.getTableName());
                
        LeaderElectionDAO leaderElectionDAO = new LeaderElectionDAO(databaseLeaderElectionConfiguration);
        leaderElectionDAO.init();
        
        // insert and delete record
        LeaderElectorRecord leaderElectorRecord = new LeaderElectorRecord(MYTEST, "instance1", "user");
        leaderElectionDAO.insertLeader(leaderElectorRecord);
        assertEquals(1, leaderElectionDAO.deleteLeaderByName(MYTEST));

        // insert new leader and delete
        leaderElectorRecord = new LeaderElectorRecord(MYTEST, "instance1", "user");
        assertEquals(1, leaderElectionDAO.insertLeader(leaderElectorRecord));
        assertEquals(1, leaderElectionDAO.deleteLeaderByNameAndId(MYTEST, leaderElectorRecord.getId()));

        // insert new leader 
        leaderElectorRecord = new LeaderElectorRecord(MYTEST, "instance1", "user");
        assertEquals(1, leaderElectionDAO.insertLeader(leaderElectorRecord));
        
        // a second will fail
        assertEquals(-1, leaderElectionDAO.insertLeader(new LeaderElectorRecord(MYTEST, "instance1", "user"))); // UC 

        // read and verify
        LeaderElectorRecord readLeaderElectorRecord = leaderElectionDAO.selectLeaderByName(MYTEST);
        LOG.debug("Read: " + leaderElectorRecord);
        assertEquals(leaderElectorRecord, readLeaderElectorRecord);

        assertNotNull(leaderElectorRecord);
    }
}
