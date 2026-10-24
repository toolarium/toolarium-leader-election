/*
 * LeaderElectionDAONonAutoCommitTest.java
 *
 * Copyright by toolarium, all rights reserved.
 */
package com.github.toolarium.leader.election.impl.database.dao;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import com.github.toolarium.leader.election.dto.db.DatabaseLeaderElectionConfiguration;
import java.io.PrintWriter;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import javax.sql.DataSource;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;


/**
 * Verifies that {@link LeaderElectionDAO} explicitly commits DML when the underlying connection
 * has {@code autoCommit=false} (Issue #10).
 *
 * <p>Connection pools configured with {@code autoCommit=false}; or connections obtained inside a
 * non-managed transaction; would silently roll back every INSERT/UPDATE when the connection is
 * returned to the pool unless the DAO calls {@code conn.commit()} itself.
 * The fix adds {@code commitIfNeeded(conn)} after every DML call: it calls {@code conn.commit()}
 * when {@code !conn.getAutoCommit()}.
 *
 * <p>The test wraps H2 with a {@link NonAutoCommitDataSource} that forces every new connection
 * to have {@code autoCommit=false}.  If {@code commitIfNeeded} is absent, the INSERT is rolled
 * back on connection close and the subsequent SELECT finds no row.
 *
 * @author patrick
 */
public class LeaderElectionDAONonAutoCommitTest {

    private static final String USER = "user";
    private static final String INST_1 = "inst-1";


    /**
     * Verify that {@link LeaderElectionDAO#insertLeader(LeaderElectorRecord)} commits the
     * write even when the connection pool returns connections with {@code autoCommit=false}.
     *
     * @throws SQLException in case of a database error
     */
    @Test
    public void testInsertCommitsWithNonAutoCommitConnection() throws SQLException {
        JdbcDataSource h2 = new JdbcDataSource();
        h2.setURL("jdbc:h2:mem:noautocommit;DB_CLOSE_DELAY=-1");
        h2.setUser("sa");
        h2.setPassword("");

        DataSource ds = new NonAutoCommitDataSource(h2);
        DatabaseLeaderElectionConfiguration config = new DatabaseLeaderElectionConfiguration(2);
        config.setDataSource(ds);

        LeaderElectionDAO dao = new LeaderElectionDAO(config);
        // H2 commits DDL (CREATE TABLE) automatically even with autoCommit=false,
        // so the table is visible after init() despite the non-autocommit wrapper.
        dao.init();

        LeaderElectorRecord rec = new LeaderElectorRecord("commit-group", INST_1, USER);
        int inserted = dao.insertLeader(rec);
        assertEquals(1, inserted, "INSERT must succeed");

        // If commitIfNeeded is missing, the INSERT was rolled back when the connection closed.
        // A fresh SELECT on a new (non-autocommit) connection must find the committed row.
        LeaderElectorRecord found = dao.selectLeaderByName("commit-group");
        assertNotNull(found,
                "Row must be visible after insertLeader(); missing commitIfNeeded() would cause a silent rollback");
        assertEquals(INST_1, found.getInstance());
    }


    /**
     * Verify that {@link LeaderElectionDAO#updateLeaderTimestamp} commits the write with a
     * non-autocommit connection so the renewed lease_ts is visible to other nodes.
     *
     * @throws SQLException in case of a database error
     */
    @Test
    public void testUpdateCommitsWithNonAutoCommitConnection() throws SQLException {
        JdbcDataSource h2 = new JdbcDataSource();
        h2.setURL("jdbc:h2:mem:noautocommit_update;DB_CLOSE_DELAY=-1");
        h2.setUser("sa");
        h2.setPassword("");

        DataSource ds = new NonAutoCommitDataSource(h2);
        DatabaseLeaderElectionConfiguration config = new DatabaseLeaderElectionConfiguration(2);
        config.setDataSource(ds);

        LeaderElectionDAO dao = new LeaderElectionDAO(config);
        dao.init();

        // Insert an initial leader record.
        LeaderElectorRecord initial = new LeaderElectorRecord("upd-group", INST_1, USER);
        assertEquals(1, dao.insertLeader(initial));

        // Renew the lease: update with a new id and a fresh timestamp.
        LeaderElectorRecord renewed = new LeaderElectorRecord("upd-group", INST_1, USER);
        int updated = dao.updateLeaderTimestamp("upd-group", INST_1, renewed.getId(), renewed.getTimestamp());
        assertEquals(1, updated, "UPDATE must succeed");

        // If commitIfNeeded is missing the UPDATE was rolled back; the SELECT would return the
        // old id.  With the fix the new id is visible.
        LeaderElectorRecord found = dao.selectLeaderByName("upd-group");
        assertNotNull(found);
        assertEquals(renewed.getId(), found.getId(),
                "Renewed record id must be visible after updateLeaderTimestamp(); missing commit would leave the old id");
    }


    /**
     * Verify that {@link LeaderElectionDAO#deleteLeaderByNameAndInstance} commits the deletion
     * with a non-autocommit connection so the row disappears for other readers immediately.
     *
     * @throws SQLException in case of a database error
     */
    @Test
    public void testDeleteCommitsWithNonAutoCommitConnection() throws SQLException {
        JdbcDataSource h2 = new JdbcDataSource();
        h2.setURL("jdbc:h2:mem:noautocommit_delete;DB_CLOSE_DELAY=-1");
        h2.setUser("sa");
        h2.setPassword("");

        DataSource ds = new NonAutoCommitDataSource(h2);
        DatabaseLeaderElectionConfiguration config = new DatabaseLeaderElectionConfiguration(2);
        config.setDataSource(ds);

        LeaderElectionDAO dao = new LeaderElectionDAO(config);
        dao.init();

        LeaderElectorRecord rec = new LeaderElectorRecord("del-group", INST_1, USER);
        assertEquals(1, dao.insertLeader(rec));

        int deleted = dao.deleteLeaderByNameAndInstance("del-group", INST_1);
        assertEquals(1, deleted, "DELETE must succeed");

        // Without commitIfNeeded the DELETE is rolled back; the row would still appear.
        LeaderElectorRecord found = dao.selectLeaderByName("del-group");
        // null means the row was really deleted (committed); non-null means the commit was missing.
        assertEquals(null, found,
                "Row must be gone after deleteLeaderByNameAndInstance(); missing commit would leave it visible");
    }


    // -----------------------------------------------------------------------
    // Helper: force every new connection to have autoCommit=false.
    // -----------------------------------------------------------------------

    /**
     * A {@link DataSource} wrapper that calls {@link Connection#setAutoCommit(boolean) setAutoCommit(false)}
     * on every connection obtained from the delegate before returning it to the caller.
     * This simulates a connection pool configured with {@code defaultAutoCommit=false}.
     */
    private static final class NonAutoCommitDataSource implements DataSource {
        private final DataSource delegate;

        /**
         * Constructor for NonAutoCommitDataSource
         *
         * @param delegate the underlying DataSource
         */
        NonAutoCommitDataSource(DataSource delegate) {
            this.delegate = delegate;
        }


        /**
         * @see javax.sql.DataSource#getConnection()
         */
        @Override
        public Connection getConnection() throws SQLException {
            Connection conn = delegate.getConnection();
            conn.setAutoCommit(false);
            return conn;
        }


        /**
         * @see javax.sql.DataSource#getConnection(String, String)
         */
        @Override
        public Connection getConnection(String username, String password) throws SQLException {
            Connection conn = delegate.getConnection(username, password);
            conn.setAutoCommit(false);
            return conn;
        }


        /**
         * @see javax.sql.CommonDataSource#getLogWriter()
         */
        @Override
        public PrintWriter getLogWriter() throws SQLException {
            return delegate.getLogWriter();
        }


        /**
         * @see javax.sql.CommonDataSource#setLogWriter(PrintWriter)
         */
        @Override
        public void setLogWriter(PrintWriter out) throws SQLException {
            delegate.setLogWriter(out);
        }


        /**
         * @see javax.sql.CommonDataSource#setLoginTimeout(int)
         */
        @Override
        public void setLoginTimeout(int seconds) throws SQLException {
            delegate.setLoginTimeout(seconds);
        }


        /**
         * @see javax.sql.CommonDataSource#getLoginTimeout()
         */
        @Override
        public int getLoginTimeout() throws SQLException {
            return delegate.getLoginTimeout();
        }


        /**
         * @see javax.sql.CommonDataSource#getParentLogger()
         */
        @Override
        public java.util.logging.Logger getParentLogger() throws SQLFeatureNotSupportedException {
            return delegate.getParentLogger();
        }


        /**
         * @see java.sql.Wrapper#unwrap(Class)
         */
        @Override
        public <T> T unwrap(Class<T> iface) throws SQLException {
            return delegate.unwrap(iface);
        }


        /**
         * @see java.sql.Wrapper#isWrapperFor(Class)
         */
        @Override
        public boolean isWrapperFor(Class<?> iface) throws SQLException {
            return delegate.isWrapperFor(iface);
        }
    }
}
