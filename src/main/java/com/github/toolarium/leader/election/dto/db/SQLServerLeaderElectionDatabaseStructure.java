/*
 * SQLServerLeaderElectionDatabaseStructure.java
 *
 * Copyright by toolarium, all rights reserved.
 */
package com.github.toolarium.leader.election.dto.db;


/**
 * Defines the SQL Server database structure using NVARCHAR. The PRIMARY KEY already creates
 * a clustered index on the key column; no additional index is needed.
 *
 * @author patrick
 */
public class SQLServerLeaderElectionDatabaseStructure extends JDBCLeaderElectionDatabaseStructure {

    /**
     * Constructor for SQLServerLeaderElectionDatabaseStructure
     */
    public SQLServerLeaderElectionDatabaseStructure() {
        super();
    }


    /**
     * @see com.github.toolarium.leader.election.dto.db.JDBCLeaderElectionDatabaseStructure#createSQLTable()
     */
    @Override
    public String createSQLTable() {
        return "CREATE TABLE " + getTableName() + "(id NVARCHAR(36) NOT NULL, name NVARCHAR(512) NOT NULL, "
                + "instance NVARCHAR(255), username NVARCHAR(255), lease_ts BIGINT NOT NULL, "
                + "CONSTRAINT pk_" + getTableName().toLowerCase() + " PRIMARY KEY (name))";
    }


    /**
     * @see com.github.toolarium.leader.election.dto.db.JDBCLeaderElectionDatabaseStructure#createSQLIndex()
     *     SQL Server PRIMARY KEY already creates a clustered index on the key column; no additional index is needed.
     */
    @Override
    public String createSQLIndex() {
        return null;
    }


    /**
     * @see com.github.toolarium.leader.election.dto.db.DatabaseLeaderElectionConfiguration.LeaderElectionDatabaseStructure#createSQLRenameTimestampColumn()
     *     SQL Server does not support {@code RENAME COLUMN}; column renaming requires {@code sp_rename}.
     *     The bracket-quoted {@code [timestamp]} handles the reserved-word collision.
     */
    @Override
    public String createSQLRenameTimestampColumn() {
        return "EXEC sp_rename '[" + getTableName() + "].[timestamp]', 'lease_ts', 'COLUMN'";
    }
}
