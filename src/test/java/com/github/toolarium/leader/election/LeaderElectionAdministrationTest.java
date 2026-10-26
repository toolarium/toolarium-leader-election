/*
 * LeaderElectionAdministrationTest.java
 *
 * Copyright by toolarium, all rights reserved.
 */
package com.github.toolarium.leader.election;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.github.toolarium.leader.election.dto.LeaderElectionInformation;
import com.github.toolarium.leader.election.dto.LeaderElectionStrategy;
import com.github.toolarium.leader.election.dto.db.DatabaseLeaderElectionConfiguration;
import com.github.toolarium.leader.election.exception.LeaderElectionException;
import com.github.toolarium.leader.election.impl.dto.LeaderElectionConfigurationSnapshot;
import java.sql.Connection;
import java.sql.Statement;
import java.time.Duration;
import java.util.Collection;
import java.util.UUID;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;


/**
 * Unit tests for {@link ILeaderElectionAdministration} obtained via
 * {@link LeaderElectionFactory#getLeaderElectionAdministration()}.
 *
 * @author patrick
 */
public class LeaderElectionAdministrationTest {


    /**
     * getLeaderElectionAdministration() must never return null.
     */
    @Test
    public void testAdminIsNonNull() {
        assertNotNull(LeaderElectionFactory.getInstance().getLeaderElectionAdministration());
    }


    /**
     * Two calls to getLeaderElectionAdministration() must return the same singleton instance.
     */
    @Test
    public void testAdminIsSameSingleton() {
        ILeaderElectionAdministration adminA = LeaderElectionFactory.getInstance().getLeaderElectionAdministration();
        ILeaderElectionAdministration adminB = LeaderElectionFactory.getInstance().getLeaderElectionAdministration();
        assertSame(adminA, adminB, "getLeaderElectionAdministration() must return the same singleton");
    }


    /**
     * forceRelease(null) must return false without throwing an exception.
     *
     * @throws LeaderElectionException In case of an error
     */
    @Test
    public void testForceReleaseNullReturnsFalse() throws LeaderElectionException {
        assertFalse(LeaderElectionFactory.getInstance().getLeaderElectionAdministration().forceRelease(null));
    }


    /**
     * forceRelease with an unknown id must return false when no elector with that id exists.
     *
     * @throws LeaderElectionException In case of an error
     */
    @Test
    public void testForceReleaseUnknownIdReturnsFalse() throws LeaderElectionException {
        assertFalse(LeaderElectionFactory.getInstance().getLeaderElectionAdministration().forceRelease("unknown-id-xyz-99999"));
    }


    /**
     * A FILE elector that holds leadership must appear in getElectors() with its correct id.
     *
     * @throws Exception In case of an error
     */
    @Test
    public void testGetElectorsContainsLocalLeader() throws Exception {
        ILeaderElector elector = LeaderElectionFactory.getInstance().getLeaderElection(
                LeaderElectionStrategy.FILE, new LeaderElectionInformation("admin-local-test"));
        try {
            assertTrue(elector.elect(Duration.ofMillis(2000)));
            ILeaderElectionAdministration admin = LeaderElectionFactory.getInstance().getLeaderElectionAdministration();
            Collection<ILeaderElectorView> views = admin.getElectors();
            assertTrue(containsId(views, elector.getId()),
                    "Active local leader must appear in getElectors(), id=" + elector.getId());

            // the view is a snapshot: it cannot be cast back to the elector to control it, and its configuration holds only the timings
            for (ILeaderElectorView view : views) {
                assertFalse(view instanceof ILeaderElector, "A view must not expose the elector");
                assertTrue(view.getLeaderElectionConfiguration() instanceof LeaderElectionConfigurationSnapshot,
                           "A view must not expose the configuration of the elector: " + view.getLeaderElectionConfiguration().getClass());
                if (elector.getId().equals(view.getId())) {
                    assertEquals("admin-local-test", view.getLeaderElectionInformation().getName());
                    assertNotNull(view.getLeaderElectorTimestamp());
                    assertTrue(view.getRemainingLeadTime().compareTo(Duration.ZERO) > 0);
                    assertEquals(((ILeaderElectorView) elector).getLeaderElectionConfiguration().getRenewDeadline(), view.getLeaderElectionConfiguration().getRenewDeadline());
                }
            }
        } finally {
            elector.close();
        }
    }


    /**
     * forceRelease on a locally-held FILE elector must close it and return true.
     * After force-release the elector must no longer report itself as leader.
     *
     * @throws Exception In case of an error
     */
    @Test
    public void testForceReleaseClosesLocalElector() throws Exception {
        ILeaderElector elector = LeaderElectionFactory.getInstance().getLeaderElection(
                LeaderElectionStrategy.FILE, new LeaderElectionInformation("admin-force-test"));
        try {
            assertTrue(elector.elect(Duration.ofMillis(2000)));
            assertTrue(elector.isLeader());
            String electId = elector.getId();
            ILeaderElectionAdministration admin = LeaderElectionFactory.getInstance().getLeaderElectionAdministration();
            assertTrue(admin.forceRelease(electId), "forceRelease must return true for a known leader id");
            assertFalse(elector.isLeader(), "Elector must not be leader after force-release");
        } finally {
            elector.close();
        }
    }


    /**
     * A record manually inserted into the DATABASE leader table (simulating a remote node)
     * must appear in getElectors() with the correct instance id.
     *
     * @throws Exception In case of an error
     */
    @Test
    public void testGetElectorsIncludesRemoteDbRecords() throws Exception {
        JdbcDataSource ds = new JdbcDataSource();
        ds.setURL("jdbc:h2:mem:admin_remote_test;DB_CLOSE_DELAY=-1");
        ds.setUser("sa");
        ds.setPassword("");
        DatabaseLeaderElectionConfiguration config = new DatabaseLeaderElectionConfiguration(2);
        config.setDataSource(ds);
        ILeaderElector elector = LeaderElectionFactory.getInstance().getLeaderElection(
                LeaderElectionStrategy.DATABASE, new LeaderElectionInformation("admin-remote-group"), config);
        try {
            assertTrue(elector.elect());
            String remoteId = "remote-node-" + System.currentTimeMillis();
            long leaseTs = System.currentTimeMillis();
            String insertSql = "INSERT INTO LeaderElection (id, name, instance, username, lease_ts) VALUES ('"
                    + UUID.randomUUID() + "', 'remote-grp', '" + remoteId + "', 'test-user', " + leaseTs + ")";
            try (Connection conn = ds.getConnection(); Statement stmt = conn.createStatement()) {
                stmt.execute(insertSql);
            }
            ILeaderElectionAdministration admin = LeaderElectionFactory.getInstance().getLeaderElectionAdministration();
            Collection<ILeaderElectorView> views = admin.getElectors();
            assertTrue(containsId(views, remoteId),
                    "Manually inserted remote DB record must appear in getElectors(), id=" + remoteId);
        } finally {
            elector.close();
        }
    }


    /**
     * forceRelease on a remote DATABASE record (identified by its instance id) must delete it
     * from the database so it no longer appears in getElectors().
     *
     * @throws Exception In case of an error
     */
    @Test
    public void testForceReleaseRemoteDbRecord() throws Exception {
        JdbcDataSource ds = new JdbcDataSource();
        ds.setURL("jdbc:h2:mem:admin_forceremote_test;DB_CLOSE_DELAY=-1");
        ds.setUser("sa");
        ds.setPassword("");
        DatabaseLeaderElectionConfiguration config = new DatabaseLeaderElectionConfiguration(2);
        config.setDataSource(ds);
        ILeaderElector elector = LeaderElectionFactory.getInstance().getLeaderElection(
                LeaderElectionStrategy.DATABASE, new LeaderElectionInformation("admin-forceremote-group"), config);
        try {
            assertTrue(elector.elect());
            String remoteId = "remote-forceable-" + System.currentTimeMillis();
            long leaseTs = System.currentTimeMillis();
            String insertSql = "INSERT INTO LeaderElection (id, name, instance, username, lease_ts) VALUES ('"
                    + UUID.randomUUID() + "', 'forceable-grp', '" + remoteId + "', 'test-user', " + leaseTs + ")";
            try (Connection conn = ds.getConnection(); Statement stmt = conn.createStatement()) {
                stmt.execute(insertSql);
            }
            ILeaderElectionAdministration admin = LeaderElectionFactory.getInstance().getLeaderElectionAdministration();
            assertTrue(containsId(admin.getElectors(), remoteId),
                    "Remote DB record must appear in getElectors() before force-release");
            assertTrue(admin.forceRelease(remoteId),
                    "forceRelease must return true for a known remote DB record id");
            // Row is NOT deleted; a force_release_deadline is set to prevent split-brain.
            // The record remains visible until the deadline elapses and a new node steals the row.
            assertTrue(containsId(admin.getElectors(), remoteId),
                    "Remote DB record still present after force-release (deadline set, row not deleted)");
            // Verify the deadline is set in the database
            String selectSql = "SELECT force_release_deadline FROM LeaderElection WHERE instance = '" + remoteId + "'";
            try (Connection conn = ds.getConnection(); java.sql.Statement chkStmt = conn.createStatement();
                 java.sql.ResultSet rs = chkStmt.executeQuery(selectSql)) {
                assertTrue(rs.next(), "Row must still exist");
                assertTrue(rs.getLong(1) > 0, "force_release_deadline must be set to a future epoch millis");
            }
        } finally {
            elector.close();
        }
    }


    /**
     * Returns {@code true} if the collection contains a view whose id equals the given id.
     *
     * @param views the collection of views to search
     * @param id the id to look for
     * @return {@code true} if a matching view was found
     */
    private boolean containsId(Collection<ILeaderElectorView> views, String id) {
        for (ILeaderElectorView view : views) {
            if (id.equals(view.getId())) {
                return true;
            }
        }
        return false;
    }
}
