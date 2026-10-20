/*
 * DatabaseElectorViewsTest.java
 *
 * Copyright by toolarium, all rights reserved.
 */
package com.github.toolarium.leader.election.impl.database;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.github.toolarium.leader.election.ILeaderElector;
import com.github.toolarium.leader.election.ILeaderElectorView;
import com.github.toolarium.leader.election.LeaderElectionFactory;
import com.github.toolarium.leader.election.dto.LeaderElectionInformation;
import com.github.toolarium.leader.election.dto.LeaderElectionStrategy;
import com.github.toolarium.leader.election.dto.db.DatabaseLeaderElectionConfiguration;
import java.sql.Connection;
import java.sql.Statement;
import java.time.Instant;
import java.util.Collection;
import java.util.UUID;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;


/**
 * Unit tests for the package-private {@link DatabaseLeaderElectorImpl#getDatabaseElectorViews()} and
 * {@link DatabaseLeaderElectorImpl#forceReleaseRemote(String)} methods and the
 * {@code DatabaseRecordView} inner class.
 *
 * @author patrick
 */
public class DatabaseElectorViewsTest {
    private static final String DB_USER = "sa";


    /**
     * Before elect() the leader table does not exist yet.
     * getDatabaseElectorViews() must return an empty collection instead of throwing.
     *
     * @throws Exception In case of an error
     */
    @Test
    public void testGetDatabaseElectorViewsEmptyBeforeElect() throws Exception {
        JdbcDataSource ds = new JdbcDataSource();
        ds.setURL("jdbc:h2:mem:dbview_before;DB_CLOSE_DELAY=-1");
        ds.setUser(DB_USER);
        ds.setPassword("");
        DatabaseLeaderElectionConfiguration config = new DatabaseLeaderElectionConfiguration(2);
        config.setDataSource(ds);
        ILeaderElector elector = LeaderElectionFactory.getInstance().getLeaderElection(
                LeaderElectionStrategy.DATABASE, new LeaderElectionInformation("dbview-before-group"), config);
        try {
            DatabaseLeaderElectorImpl dbElector = (DatabaseLeaderElectorImpl) elector;
            Collection<ILeaderElectorView> views = dbElector.getDatabaseElectorViews();
            assertNotNull(views, "getDatabaseElectorViews() must never return null");
            assertTrue(views.isEmpty(), "getDatabaseElectorViews() must return an empty collection before elect()");
        } finally {
            elector.close();
        }
    }


    /**
     * After elect() the local leader record is stored in the database.
     * getDatabaseElectorViews() must return at least one view whose id equals the elector's id.
     *
     * @throws Exception In case of an error
     */
    @Test
    public void testGetDatabaseElectorViewsContainsLeaderAfterElect() throws Exception {
        JdbcDataSource ds = new JdbcDataSource();
        ds.setURL("jdbc:h2:mem:dbview_elect;DB_CLOSE_DELAY=-1");
        ds.setUser(DB_USER);
        ds.setPassword("");
        DatabaseLeaderElectionConfiguration config = new DatabaseLeaderElectionConfiguration(2);
        config.setDataSource(ds);
        ILeaderElector elector = LeaderElectionFactory.getInstance().getLeaderElection(
                LeaderElectionStrategy.DATABASE, new LeaderElectionInformation("dbview-group"), config);
        try {
            assertTrue(elector.elect());
            DatabaseLeaderElectorImpl dbElector = (DatabaseLeaderElectorImpl) elector;
            Collection<ILeaderElectorView> views = dbElector.getDatabaseElectorViews();
            assertFalse(views.isEmpty(), "getDatabaseElectorViews() must not be empty after elect()");
            ILeaderElectorView view = findById(views, elector.getId());
            assertNotNull(view, "getDatabaseElectorViews() must contain a view for the elected instance id");
            assertEquals(elector.getId(), view.getId());
        } finally {
            elector.close();
        }
    }


    /**
     * The DatabaseRecordView returned by getDatabaseElectorViews() must expose a non-null
     * leader election information, configuration, and a recent timestamp.
     *
     * @throws Exception In case of an error
     */
    @Test
    public void testDatabaseRecordViewFields() throws Exception {
        JdbcDataSource ds = new JdbcDataSource();
        ds.setURL("jdbc:h2:mem:dbview_fields;DB_CLOSE_DELAY=-1");
        ds.setUser(DB_USER);
        ds.setPassword("");
        DatabaseLeaderElectionConfiguration config = new DatabaseLeaderElectionConfiguration(2);
        config.setDataSource(ds);
        ILeaderElector elector = LeaderElectionFactory.getInstance().getLeaderElection(
                LeaderElectionStrategy.DATABASE, new LeaderElectionInformation("dbview-fields-group"), config);
        try {
            assertTrue(elector.elect());
            DatabaseLeaderElectorImpl dbElector = (DatabaseLeaderElectorImpl) elector;
            Collection<ILeaderElectorView> views = dbElector.getDatabaseElectorViews();
            ILeaderElectorView view = findById(views, elector.getId());
            assertNotNull(view, "View for elected instance must be present");

            assertNotNull(view.getLeaderElectionInformation(), "getLeaderElectionInformation() must not be null");
            assertNotNull(view.getLeaderElectionConfiguration(), "getLeaderElectionConfiguration() must not be null");
            assertNotNull(view.getLeaderElectorTimestamp(), "getLeaderElectorTimestamp() must not be null");

            Instant tenSecondsAgo = Instant.now().minusSeconds(10);
            assertTrue(view.getLeaderElectorTimestamp().isAfter(tenSecondsAgo),
                    "Timestamp must be within the last 10 seconds");
        } finally {
            elector.close();
        }
    }


    /**
     * getDatabaseElectorViews() must include a record that was manually inserted into the database
     * to simulate a remote node's leader entry.
     *
     * @throws Exception In case of an error
     */
    @Test
    public void testGetDatabaseElectorViewsIncludesManuallyInsertedRecord() throws Exception {
        JdbcDataSource ds = new JdbcDataSource();
        ds.setURL("jdbc:h2:mem:dbview_manual;DB_CLOSE_DELAY=-1");
        ds.setUser(DB_USER);
        ds.setPassword("");
        DatabaseLeaderElectionConfiguration config = new DatabaseLeaderElectionConfiguration(2);
        config.setDataSource(ds);
        ILeaderElector elector = LeaderElectionFactory.getInstance().getLeaderElection(
                LeaderElectionStrategy.DATABASE, new LeaderElectionInformation("dbview-manual-group"), config);
        try {
            assertTrue(elector.elect());
            String remoteId = "remote-manual-" + System.currentTimeMillis();
            long leaseTs = System.currentTimeMillis();
            String insertSql = "INSERT INTO LeaderElection (id, name, instance, username, lease_ts) VALUES ('"
                    + UUID.randomUUID() + "', 'manual-grp', '" + remoteId + "', 'test-user', " + leaseTs + ")";
            try (Connection conn = ds.getConnection(); Statement stmt = conn.createStatement()) {
                stmt.execute(insertSql);
            }
            DatabaseLeaderElectorImpl dbElector = (DatabaseLeaderElectorImpl) elector;
            Collection<ILeaderElectorView> views = dbElector.getDatabaseElectorViews();
            ILeaderElectorView remoteView = findById(views, remoteId);
            assertNotNull(remoteView,
                    "Manually inserted remote record must be visible via getDatabaseElectorViews()");
            assertEquals(remoteId, remoteView.getId());
        } finally {
            elector.close();
        }
    }


    /**
     * forceReleaseRemote with an unknown instance id must return false without throwing.
     *
     * @throws Exception In case of an error
     */
    @Test
    public void testForceReleaseRemoteReturnsFalseForUnknown() throws Exception {
        JdbcDataSource ds = new JdbcDataSource();
        ds.setURL("jdbc:h2:mem:dbview_release_unknown;DB_CLOSE_DELAY=-1");
        ds.setUser(DB_USER);
        ds.setPassword("");
        DatabaseLeaderElectionConfiguration config = new DatabaseLeaderElectionConfiguration(2);
        config.setDataSource(ds);
        ILeaderElector elector = LeaderElectionFactory.getInstance().getLeaderElection(
                LeaderElectionStrategy.DATABASE, new LeaderElectionInformation("dbview-release-unknown-group"), config);
        try {
            assertTrue(elector.elect());
            DatabaseLeaderElectorImpl dbElector = (DatabaseLeaderElectorImpl) elector;
            assertFalse(dbElector.forceReleaseRemote("non-existent-instance-id"),
                    "forceReleaseRemote must return false for an unknown instance id");
        } finally {
            elector.close();
        }
    }


    /**
     * forceReleaseRemote with the instance id of a manually inserted record must delete it
     * from the database. Afterward getDatabaseElectorViews() must no longer contain that record.
     *
     * @throws Exception In case of an error
     */
    @Test
    public void testForceReleaseRemoteDeletesMatchingRow() throws Exception {
        JdbcDataSource ds = new JdbcDataSource();
        ds.setURL("jdbc:h2:mem:dbview_release_row;DB_CLOSE_DELAY=-1");
        ds.setUser(DB_USER);
        ds.setPassword("");
        DatabaseLeaderElectionConfiguration config = new DatabaseLeaderElectionConfiguration(2);
        config.setDataSource(ds);
        ILeaderElector elector = LeaderElectionFactory.getInstance().getLeaderElection(
                LeaderElectionStrategy.DATABASE, new LeaderElectionInformation("dbview-release-row-group"), config);
        try {
            assertTrue(elector.elect());
            String remoteId = "remote-to-release-" + System.currentTimeMillis();
            long leaseTs = System.currentTimeMillis();
            String insertSql = "INSERT INTO LeaderElection (id, name, instance, username, lease_ts) VALUES ('"
                    + UUID.randomUUID() + "', 'release-grp', '" + remoteId + "', 'test-user', " + leaseTs + ")";
            try (Connection conn = ds.getConnection(); Statement stmt = conn.createStatement()) {
                stmt.execute(insertSql);
            }
            DatabaseLeaderElectorImpl dbElector = (DatabaseLeaderElectorImpl) elector;
            assertNotNull(findById(dbElector.getDatabaseElectorViews(), remoteId),
                    "Manually inserted record must be visible before forceReleaseRemote");
            assertTrue(dbElector.forceReleaseRemote(remoteId),
                    "forceReleaseRemote must return true for a known instance id");
            assertNull(findById(dbElector.getDatabaseElectorViews(), remoteId),
                    "Record must be gone from getDatabaseElectorViews() after forceReleaseRemote");
        } finally {
            elector.close();
        }
    }


    /**
     * Returns the first view in the collection whose id equals the given id, or {@code null} if not found.
     *
     * @param views the collection of views to search
     * @param id the id to look for
     * @return the matching view or {@code null}
     */
    private ILeaderElectorView findById(Collection<ILeaderElectorView> views, String id) {
        for (ILeaderElectorView view : views) {
            if (id.equals(view.getId())) {
                return view;
            }
        }
        return null;
    }
}
