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
    /** Maximum table name length for SQL Server. */
    public static final int MAX_TABLE_NAME_LENGTH = 128;


    /**
     * Constructor for SQLServerLeaderElectionDatabaseStructure
     */
    public SQLServerLeaderElectionDatabaseStructure() {
        super();
    }


    /**
     * @see com.github.toolarium.leader.election.dto.db.JDBCLeaderElectionDatabaseStructure#getMaxTableNameLength()
     */
    @Override
    public int getMaxTableNameLength() {
        return MAX_TABLE_NAME_LENGTH;
    }


    /**
     * @see com.github.toolarium.leader.election.dto.db.JDBCLeaderElectionDatabaseStructure#createSQLTable()
     */
    @Override
    public String createSQLTable() {
        return "CREATE TABLE " + getTableName() + "(id NVARCHAR(36) NOT NULL, name NVARCHAR(512) NOT NULL, "
                + "instance NVARCHAR(255), username NVARCHAR(255), lease_ts BIGINT NOT NULL, "
                + "force_release_deadline BIGINT NULL, "
                + "CONSTRAINT pk_" + getConstraintBaseName() + " PRIMARY KEY (name))";
    }


    /**
     * @see com.github.toolarium.leader.election.dto.db.DatabaseLeaderElectionConfiguration.LeaderElectionDatabaseStructure#createSQLAddForceReleaseDeadlineColumn()
     *     SQL Server uses ADD without the COLUMN keyword.
     */
    @Override
    public String createSQLAddForceReleaseDeadlineColumn() {
        return "ALTER TABLE " + getTableName() + " ADD force_release_deadline BIGINT NULL";
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


    /**
     * @see com.github.toolarium.leader.election.dto.db.JDBCLeaderElectionDatabaseStructure#createSQLSelectCurrentTimestampMillis()
     *     {@code CURRENT_TIMESTAMP} is the server's local time without zone information; reading it via
     *     {@code ResultSet.getTimestamp()} interprets it in the JVM time zone, so nodes running in
     *     different time zones would compute different lease clocks. The epoch value is computed from
     *     UTC on the server side instead ({@code DATEDIFF_BIG} requires SQL Server 2016 or later).
     */
    @Override
    public String createSQLSelectCurrentTimestampMillis() {
        return "SELECT DATEDIFF_BIG(MILLISECOND, '1970-01-01', SYSUTCDATETIME())";
    }
}
