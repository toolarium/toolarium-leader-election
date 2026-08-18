/*
 * LeaderElectionDAO.java
 *
 * Copyright by toolarium, all rights reserved.
 */
package com.github.toolarium.leader.election.impl.database.dao;

import com.github.toolarium.leader.election.dto.DatabaseLeaderElectionConfiguration;
import com.github.toolarium.leader.election.dto.DatabaseLeaderElectionConfiguration.LeaderElectionDatabaseStructure;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.SQLIntegrityConstraintViolationException;
import java.sql.Statement;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


/**
 * Leader election database access object
 * 
 * @author patrick
 */
public class LeaderElectionDAO {
    private static final Logger LOG = LoggerFactory.getLogger(LeaderElectionDAO.class);
    private DatabaseLeaderElectionConfiguration leaderElectionConfiguration;
    private LeaderElectionDatabaseStructure leaderElectionDatabaseStructure;
    private Connection connection;
    
    
    /**
     * Constructor for LeaderElectionDAO
     *
     * @param leaderElectionConfiguration the leader election database configuration
     */
    public LeaderElectionDAO(DatabaseLeaderElectionConfiguration leaderElectionConfiguration) {
        this.leaderElectionConfiguration = leaderElectionConfiguration;
        this.leaderElectionDatabaseStructure = leaderElectionConfiguration.getLeaderElectionDatabaseStructure();
        this.connection = null;
    }


    /**
     * Initialize
     *
     * @throws SQLException In case of a database exception
     */
    public void init() throws SQLException {
        connection = leaderElectionConfiguration.getDataSource().getConnection();
        
        int timeout = Long.valueOf(leaderElectionConfiguration.getRenewDeadline().toSeconds()).intValue();
        if (connection == null || !connection.isValid(timeout)) {
            throw new SQLException("Could not get valid database connection!");
        }

        boolean recreate = false;
        boolean existTable = existDatabaseTable();
        if (existTable && recreate) {
            LOG.debug("Drop leader election table " + leaderElectionDatabaseStructure.getTableName() + "!");
            Statement stmt = connection.createStatement();
            try {
                stmt.execute(leaderElectionDatabaseStructure.createSQLDropTable());
            } finally {
                JDBCUtil.getInstance().closeStmnt(stmt);
            }
        }

        if (existDatabaseTable()) {
            LOG.debug("Leader election table " + leaderElectionDatabaseStructure.getTableName() + " exists.");
        } else {
            LOG.debug("Create leader election table " + leaderElectionDatabaseStructure.getTableName() + "...");
            Statement stmt = connection.createStatement();
            try {
                stmt.execute(leaderElectionDatabaseStructure.createSQLTable());
            } finally {
                JDBCUtil.getInstance().closeStmnt(stmt);
            }
            String createIndexSql = leaderElectionDatabaseStructure.createSQLIndex();
            if (createIndexSql != null) {
                Statement idxStmt = connection.createStatement();
                try {
                    idxStmt.execute(createIndexSql);
                } finally {
                    JDBCUtil.getInstance().closeStmnt(idxStmt);
                }
            }
        }
    }
    

    /**
     * Close connection
     */
    public void close() {
        JDBCUtil.getInstance().closeConn(connection);
        connection = null;
    }

    
    /**
     * Insert a new leader
     *
     * @param leaderElectorRecord the leader record
     * @return the number of inserted records
     * @throws SQLException In case of a database error
     */
    public int insertLeader(LeaderElectorRecord leaderElectorRecord) throws SQLException {
        validateConnection();
        
        PreparedStatement stmt = connection.prepareStatement(leaderElectionDatabaseStructure.createSQLInsertLeader());
        
        try {
            JDBCUtil.getInstance().setValues(stmt, new Object[] {leaderElectorRecord.getId(), leaderElectorRecord.getName(), leaderElectorRecord.getInstance(), leaderElectorRecord.getUser(), leaderElectorRecord.getTimestamp()});
            return stmt.executeUpdate();
        } catch (SQLIntegrityConstraintViolationException uc) {
            return -1;
        } catch (SQLException e) {
            throw e;
        } finally {
            JDBCUtil.getInstance().closeStmnt(stmt);
        }
    }


    /**
     * Delete a leader by name
     * 
     * @param name the name
     * @return the number of deleted records
     * @throws SQLException In case of a database error
     */
    public int deleteLeaderByName(String name) throws SQLException {
        validateConnection();
        
        PreparedStatement stmt = connection.prepareStatement(leaderElectionDatabaseStructure.createSQLDeleteLeaderByName());
        try {
            JDBCUtil.getInstance().setValues(stmt, new Object[] {name}); // name
            return stmt.executeUpdate();
        } catch (SQLException e) {
            throw e;
        } finally {
            JDBCUtil.getInstance().closeStmnt(stmt);
        }
    }

    
    /**
     * Delete a leader by name and id
     * 
     * @param name the name
     * @param id the id
     * @return the number of deleted records
     * @throws SQLException In case of a database error
     */
    public int deleteLeaderByNameAndId(String name, String id) throws SQLException {
        validateConnection();
        
        PreparedStatement stmt = connection.prepareStatement(leaderElectionDatabaseStructure.createSQLDeleteLeaderByNameAndId());
        try {
            JDBCUtil.getInstance().setValues(stmt, new Object[] {name, id}); // name, id
            return stmt.executeUpdate();
        } catch (SQLException e) {
            throw e;
        } finally {
            JDBCUtil.getInstance().closeStmnt(stmt);
        }
    }

    
    /**
     * Select leader by name
     *
     * @param name the name of the leader to select
     * @return the leader 
     * @throws SQLException In case of a database error
     */
    public LeaderElectorRecord selectLeaderByName(String name) throws SQLException {
        validateConnection();
        
        PreparedStatement stmt = connection.prepareStatement(leaderElectionDatabaseStructure.createSQLSelectLeaderByName());
        try {
            JDBCUtil.getInstance().setValues(stmt, new Object[] {name}); // name
            ResultSet rs = stmt.executeQuery();
            try {
                if (rs.next()) {
                    Object[] columns = JDBCUtil.getInstance().getValues(rs);
                    LeaderElectorRecord leaderElectorRecord = new LeaderElectorRecord((String) columns[0], (String) columns[1], (String) columns[2], (String) columns[3], (String) columns[4]);
                    LOG.debug("LeaderElectorRecord found: " + leaderElectorRecord);
                    return leaderElectorRecord;
                }
            } finally {
                rs.close();
            }
        } catch (SQLException e) {
            throw e;
        } finally {
            JDBCUtil.getInstance().closeStmnt(stmt);
        }
        
        return null;
    } 

    
    /**
     * Check if table on database exist
     *
     * @return true if it exists
     * @throws SQLException In case of a SQL error
     */
    protected boolean existDatabaseTable() throws SQLException {
        validateConnection();
        String tableName = leaderElectionDatabaseStructure.getTableName();
        DatabaseMetaData metaData = connection.getMetaData();
        String schema = null;
        try {
            schema = connection.getSchema();
        } catch (Exception e) {
            LOG.debug("Could not retrieve schema, searching all schemas: " + e.getMessage());
        }
        ResultSet rs = metaData.getTables(null, schema, "%", new String[] {"TABLE"});
        try {
            while (rs.next()) {
                String name = rs.getString("TABLE_NAME");
                if (name != null && name.equalsIgnoreCase(tableName)) {
                    LOG.debug("Table [" + tableName + "] found.");
                    return true;
                }
            }
        } finally {
            rs.close();
        }
        LOG.debug("Table [" + tableName + "] not found.");
        return false;
    }

    
    /**
     * Update the leader timestamp atomically. Only succeeds if name and instance still match.
     *
     * @param name the name
     * @param instanceId the instance id used to match the row (stable leader elector id)
     * @param newId the new record id
     * @param timestamp the new timestamp
     * @return the number of updated records (1 on success, 0 if we are no longer the leader)
     * @throws SQLException In case of a database error
     */
    public int updateLeaderTimestamp(String name, String instanceId, String newId, String timestamp) throws SQLException {
        validateConnection();

        PreparedStatement stmt = connection.prepareStatement(leaderElectionDatabaseStructure.createSQLUpdateLeaderTimestamp());
        try {
            JDBCUtil.getInstance().setValues(stmt, new Object[] {newId, timestamp, name, instanceId});
            return stmt.executeUpdate();
        } catch (SQLException e) {
            throw e;
        } finally {
            JDBCUtil.getInstance().closeStmnt(stmt);
        }
    }


    /**
     * Validate the connection, reconnecting from the DataSource if the connection is stale.
     *
     * @throws SQLException In case a valid connection cannot be obtained
     */
    private void validateConnection() throws SQLException {
        if (connection == null || !connection.isValid(1)) {
            LOG.debug("Database connection is stale, attempting to reconnect...");
            JDBCUtil.getInstance().closeConn(connection);
            connection = leaderElectionConfiguration.getDataSource().getConnection();
            if (connection == null || !connection.isValid(1)) {
                throw new SQLException("Could not obtain a valid database connection!");
            }
            LOG.debug("Database connection re-established.");
        }
    }
}
