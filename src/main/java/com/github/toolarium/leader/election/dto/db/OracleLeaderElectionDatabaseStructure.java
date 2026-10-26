/*
 * OracleLeaderElectionDatabaseStructure.java
 *
 * Copyright by toolarium, all rights reserved.
 */
package com.github.toolarium.leader.election.dto.db;


/**
 * Defines the Oracle database structure using VARCHAR2 and no trailing semicolons.
 *
 * @author patrick
 */
public class OracleLeaderElectionDatabaseStructure extends JDBCLeaderElectionDatabaseStructure {

    /**
     * Constructor for OracleLeaderElectionDatabaseStructure
     */
    public OracleLeaderElectionDatabaseStructure() {
        super();
    }


    /**
     * @see com.github.toolarium.leader.election.dto.db.JDBCLeaderElectionDatabaseStructure#createSQLTable()
     *     Oracle uses VARCHAR2 and NUMBER(19). The PRIMARY KEY constraint name is derived from the
     *     table name, truncated to 27 characters so that the full "pk_" prefix stays within Oracle's
     *     30-character identifier limit (Oracle 12.1 and earlier).
     */
    @Override
    public String createSQLTable() {
        String base = getConstraintBaseName();
        String suffix;
        if (base.length() > 27) {
            suffix = base.substring(0, 27);
        } else {
            suffix = base;
        }
        return "CREATE TABLE " + getTableName() + "(id VARCHAR2(36) NOT NULL, name VARCHAR2(512) NOT NULL, "
                + "instance VARCHAR2(255), username VARCHAR2(255), lease_ts NUMBER(19) NOT NULL, "
                + "force_release_deadline NUMBER(19) NULL, "
                + "CONSTRAINT pk_" + suffix + " PRIMARY KEY (name))";
    }


    /**
     * @see com.github.toolarium.leader.election.dto.db.DatabaseLeaderElectionConfiguration.LeaderElectionDatabaseStructure#createSQLAddForceReleaseDeadlineColumn()
     *     Oracle uses parentheses around the column definition in ADD and NUMBER(19) for 64-bit integers.
     */
    @Override
    public String createSQLAddForceReleaseDeadlineColumn() {
        return "ALTER TABLE " + getTableName() + " ADD (force_release_deadline NUMBER(19) NULL)";
    }


    /**
     * @see com.github.toolarium.leader.election.dto.db.JDBCLeaderElectionDatabaseStructure#createSQLIndex()
     *     Returns a secondary index on the {@code instance} column.
     *     The index name is truncated so that {@code idx_} (4) + tableName + {@code _instance} (9)
     *     stays within Oracle's 30-character identifier limit (Oracle 12.1 and earlier).
     */
    @Override
    public String createSQLIndex() {
        String base = getSafeName(getTableName());
        String suffix;
        if (base.length() > 17) {
            suffix = base.substring(0, 17);
        } else {
            suffix = base;
        }
        return "CREATE INDEX idx_" + suffix + "_instance ON " + getTableName() + "(instance)";
    }


    /**
     * @see com.github.toolarium.leader.election.dto.db.JDBCLeaderElectionDatabaseStructure#createSQLSelectCurrentTimestampMillis()
     *     Oracle requires a FROM clause even for pseudo-column queries.
     */
    @Override
    public String createSQLSelectCurrentTimestampMillis() {
        return "SELECT CURRENT_TIMESTAMP FROM DUAL";
    }


    /**
     * @see com.github.toolarium.leader.election.dto.db.DatabaseLeaderElectionConfiguration.LeaderElectionDatabaseStructure#createSQLRenameTimestampColumn()
     *     Oracle stores unquoted identifiers as uppercase, so the legacy {@code timestamp} column is
     *     stored as {@code TIMESTAMP}. Using the uppercase unquoted form lets Oracle find it
     *     case-insensitively (Oracle DDL is case-insensitive for unquoted identifiers).
     */
    @Override
    public String createSQLRenameTimestampColumn() {
        return "ALTER TABLE " + getTableName() + " RENAME COLUMN TIMESTAMP TO lease_ts";
    }
}
