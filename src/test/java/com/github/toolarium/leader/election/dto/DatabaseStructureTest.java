/*
 * DatabaseStructureTest.java
 *
 * Copyright by toolarium, all rights reserved.
 */
package com.github.toolarium.leader.election.dto;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.github.toolarium.leader.election.dto.db.DatabaseLeaderElectionConfiguration;
import com.github.toolarium.leader.election.dto.db.JDBCLeaderElectionDatabaseStructure;
import com.github.toolarium.leader.election.dto.db.MariaDBLeaderElectionDatabaseStructure;
import com.github.toolarium.leader.election.dto.db.MySQLLeaderElectionDatabaseStructure;
import com.github.toolarium.leader.election.dto.db.OracleLeaderElectionDatabaseStructure;
import com.github.toolarium.leader.election.dto.db.SQLServerLeaderElectionDatabaseStructure;
import org.junit.jupiter.api.Test;


/**
 * Test the database structure SQL generation for all supported databases.
 *
 * @author patrick
 */
public class DatabaseStructureTest {

    private static final String DEFAULT_TABLE = "LeaderElection";
    private static final String SEMICOLON = ";";
    private static final String CUSTOM_TABLE = "MyCustomTable";
    private static final String LEASE_TS_COL = "lease_ts";
    private static final String INSTANCE_COL = "instance";
    private static final String FORCE_RELEASE_DEADLINE_COL = "force_release_deadline";


    /**
     * Test the default (generic) LeaderElectionDatabaseStructure SQL statements.
     */
    @Test
    public void testDefaultStructure() {
        DatabaseLeaderElectionConfiguration.LeaderElectionDatabaseStructure s =
                new DatabaseLeaderElectionConfiguration.LeaderElectionDatabaseStructure();

        assertTrue(s.createSQLTable().contains(DEFAULT_TABLE));
        assertTrue(s.createSQLTable().endsWith(SEMICOLON));
        assertNull(s.createSQLIndex());
        assertTrue(s.createSQLDropTable().contains(DEFAULT_TABLE));
        assertTrue(s.createSQLDropTable().endsWith(SEMICOLON));
        assertTrue(s.createSQLInsertLeader().contains(DEFAULT_TABLE));
        assertTrue(s.createSQLInsertLeader().endsWith(SEMICOLON));
        assertTrue(s.createSQLDeleteLeaderByName().contains(DEFAULT_TABLE));
        assertTrue(s.createSQLDeleteLeaderByName().endsWith(SEMICOLON));
        assertTrue(s.createSQLDeleteLeaderByNameAndInstance().contains(DEFAULT_TABLE));
        assertTrue(s.createSQLDeleteLeaderByInstance().contains(DEFAULT_TABLE));
        assertTrue(s.createSQLDeleteLeaderByInstance().endsWith(SEMICOLON));
        assertTrue(s.createSQLDeleteLeaderByNameAndInstance().endsWith(SEMICOLON));
        assertTrue(s.createSQLSelectLeaderByName().contains(DEFAULT_TABLE));
        assertTrue(s.createSQLSelectLeaderByName().endsWith(SEMICOLON));
        assertTrue(s.createSQLUpdateLeaderTimestamp().contains(DEFAULT_TABLE));
        assertTrue(s.createSQLUpdateLeaderTimestamp().contains("WHERE name = ? AND instance = ?"));
        assertTrue(s.createSQLUpdateLeaderTimestamp().contains(FORCE_RELEASE_DEADLINE_COL + " IS NULL"));
        assertTrue(s.createSQLUpdateLeaderTimestamp().endsWith(SEMICOLON));
        assertTrue(s.createSQLStealLeadership().contains(DEFAULT_TABLE));
        assertTrue(s.createSQLStealLeadership().contains("lease_ts < ?"));
        assertTrue(s.createSQLStealLeadership().contains(FORCE_RELEASE_DEADLINE_COL));
        assertTrue(s.createSQLStealLeadership().contains(FORCE_RELEASE_DEADLINE_COL + " = NULL"));
        assertTrue(s.createSQLStealLeadership().endsWith(SEMICOLON));
        assertNotNull(s.createSQLSetForceReleaseDeadline());
        assertTrue(s.createSQLSetForceReleaseDeadline().contains(DEFAULT_TABLE));
        assertTrue(s.createSQLSetForceReleaseDeadline().contains(FORCE_RELEASE_DEADLINE_COL));
        assertTrue(s.createSQLSetForceReleaseDeadline().contains("force_release_deadline IS NULL"));
        assertTrue(s.createSQLSetForceReleaseDeadline().endsWith(SEMICOLON));
        assertNotNull(s.createSQLAddForceReleaseDeadlineColumn());
        assertTrue(s.createSQLAddForceReleaseDeadlineColumn().contains(DEFAULT_TABLE));
        assertTrue(s.createSQLAddForceReleaseDeadlineColumn().contains(FORCE_RELEASE_DEADLINE_COL));
        assertTrue(s.createSQLAddForceReleaseDeadlineColumn().endsWith(SEMICOLON));
        assertNotNull(s.createSQLSelectCurrentTimestampMillis());
        assertTrue(s.createSQLSelectCurrentTimestampMillis().toUpperCase().contains("CURRENT_TIMESTAMP"));
        assertTrue(s.createSQLRenameTimestampColumn().contains(DEFAULT_TABLE));
        assertTrue(s.createSQLRenameTimestampColumn().contains("timestamp"));
        assertTrue(s.createSQLRenameTimestampColumn().contains(LEASE_TS_COL));
        assertTrue(s.createSQLRenameTimestampColumn().endsWith(SEMICOLON));
        assertTrue(s.createSQLSelectAll().contains(DEFAULT_TABLE));
        assertTrue(s.createSQLSelectAll().contains(LEASE_TS_COL));
        assertTrue(s.createSQLSelectAll().contains(FORCE_RELEASE_DEADLINE_COL));
        assertTrue(s.createSQLSelectAll().endsWith(SEMICOLON));
    }


    /**
     * Test JDBCLeaderElectionDatabaseStructure SQL: no trailing semicolons, standard varchar.
     */
    @Test
    public void testJDBCStructure() {
        JDBCLeaderElectionDatabaseStructure s = new JDBCLeaderElectionDatabaseStructure();

        assertTrue(s.createSQLTable().contains(DEFAULT_TABLE));
        assertTrue(s.createSQLTable().contains("varchar(36)"));
        assertTrue(s.createSQLTable().contains("bigint"));
        assertTrue(!s.createSQLTable().endsWith(SEMICOLON));
        assertNotNull(s.createSQLIndex());
        assertTrue(s.createSQLIndex().contains(INSTANCE_COL));
        assertTrue(s.createSQLIndex().contains(DEFAULT_TABLE));
        assertTrue(!s.createSQLIndex().endsWith(SEMICOLON));
        assertTrue(s.createSQLDropTable().contains(DEFAULT_TABLE));
        assertTrue(!s.createSQLDropTable().endsWith(SEMICOLON));
        assertTrue(s.createSQLInsertLeader().contains(DEFAULT_TABLE));
        assertTrue(!s.createSQLInsertLeader().endsWith(SEMICOLON));
        assertTrue(s.createSQLDeleteLeaderByName().contains("WHERE name = ?"));
        assertTrue(s.createSQLDeleteLeaderByNameAndInstance().contains("WHERE name = ? AND instance = ?"));
        assertTrue(s.createSQLDeleteLeaderByInstance().contains(DEFAULT_TABLE));
        assertTrue(s.createSQLDeleteLeaderByInstance().contains("WHERE instance = ?"));
        assertTrue(!s.createSQLDeleteLeaderByInstance().endsWith(SEMICOLON));
        assertTrue(s.createSQLSelectLeaderByName().contains("SELECT id, name"));
        assertTrue(s.createSQLSelectLeaderByName().contains(FORCE_RELEASE_DEADLINE_COL));
        assertTrue(s.createSQLUpdateLeaderTimestamp().contains(DEFAULT_TABLE));
        assertTrue(s.createSQLUpdateLeaderTimestamp().contains("WHERE name = ? AND instance = ?"));
        assertTrue(s.createSQLUpdateLeaderTimestamp().contains(FORCE_RELEASE_DEADLINE_COL + " IS NULL"));
        assertTrue(!s.createSQLUpdateLeaderTimestamp().endsWith(SEMICOLON));
        assertTrue(s.createSQLStealLeadership().contains(DEFAULT_TABLE));
        assertTrue(s.createSQLStealLeadership().contains("lease_ts < ?"));
        assertTrue(s.createSQLStealLeadership().contains(FORCE_RELEASE_DEADLINE_COL));
        assertTrue(s.createSQLStealLeadership().contains(FORCE_RELEASE_DEADLINE_COL + " = NULL"));
        assertTrue(!s.createSQLStealLeadership().endsWith(SEMICOLON));
        assertNotNull(s.createSQLSetForceReleaseDeadline());
        assertTrue(s.createSQLSetForceReleaseDeadline().contains(FORCE_RELEASE_DEADLINE_COL));
        assertTrue(s.createSQLSetForceReleaseDeadline().contains("force_release_deadline IS NULL"));
        assertTrue(!s.createSQLSetForceReleaseDeadline().endsWith(SEMICOLON));
        assertNotNull(s.createSQLAddForceReleaseDeadlineColumn());
        assertTrue(s.createSQLAddForceReleaseDeadlineColumn().contains(DEFAULT_TABLE));
        assertTrue(s.createSQLAddForceReleaseDeadlineColumn().contains(FORCE_RELEASE_DEADLINE_COL));
        assertTrue(!s.createSQLAddForceReleaseDeadlineColumn().endsWith(SEMICOLON));
        assertNotNull(s.createSQLSelectCurrentTimestampMillis());
        assertTrue(!s.createSQLSelectCurrentTimestampMillis().endsWith(SEMICOLON));
        assertTrue(s.createSQLRenameTimestampColumn().contains(DEFAULT_TABLE));
        assertTrue(s.createSQLRenameTimestampColumn().contains("timestamp"));
        assertTrue(s.createSQLRenameTimestampColumn().contains(LEASE_TS_COL));
        assertTrue(!s.createSQLRenameTimestampColumn().endsWith(SEMICOLON));
        // Standard RENAME COLUMN syntax (no backtick, no sp_rename)
        assertTrue(s.createSQLRenameTimestampColumn().toUpperCase().contains("RENAME COLUMN"));
        assertTrue(s.createSQLSelectAll().contains(DEFAULT_TABLE));
        assertTrue(s.createSQLSelectAll().contains(LEASE_TS_COL));
        assertTrue(!s.createSQLSelectAll().endsWith(SEMICOLON));
    }


    /**
     * Test MySQLLeaderElectionDatabaseStructure: no index (PRIMARY KEY creates it), constraint-based PK,
     * ROW_FORMAT=DYNAMIC for large varchar primary key.
     */
    @Test
    public void testMySQLStructure() {
        MySQLLeaderElectionDatabaseStructure s = new MySQLLeaderElectionDatabaseStructure();

        assertTrue(s.createSQLTable().contains("CONSTRAINT pk_"));
        assertTrue(s.createSQLTable().contains("NOT NULL"));
        assertTrue(s.createSQLTable().contains("ROW_FORMAT=DYNAMIC"));
        assertNotNull(s.createSQLIndex());
        assertTrue(s.createSQLIndex().contains(INSTANCE_COL));
        assertTrue(s.createSQLUpdateLeaderTimestamp().contains(DEFAULT_TABLE));
        assertTrue(!s.createSQLUpdateLeaderTimestamp().endsWith(SEMICOLON));
        // MySQL uses backtick-quoting for the reserved word 'timestamp'
        assertTrue(s.createSQLRenameTimestampColumn().contains("`timestamp`"));
        assertTrue(s.createSQLRenameTimestampColumn().contains(LEASE_TS_COL));
        // Epoch millis computed on the server: CURRENT_TIMESTAMP alone is zone-less (JVM time zone dependent)
        assertTrue(s.createSQLSelectCurrentTimestampMillis().contains("UNIX_TIMESTAMP"));
    }


    /**
     * Test MariaDBLeaderElectionDatabaseStructure: inherits from MySQL structure.
     */
    @Test
    public void testMariaDBStructure() {
        MariaDBLeaderElectionDatabaseStructure s = new MariaDBLeaderElectionDatabaseStructure();

        assertTrue(s.createSQLTable().contains("CONSTRAINT pk_"));
        assertTrue(s.createSQLTable().contains("ROW_FORMAT=DYNAMIC"));
        assertNotNull(s.createSQLIndex());
        assertTrue(s.createSQLIndex().contains(INSTANCE_COL));
        assertTrue(s.createSQLUpdateLeaderTimestamp().contains(DEFAULT_TABLE));
        assertTrue(!s.createSQLUpdateLeaderTimestamp().endsWith(SEMICOLON));
        // MariaDB inherits MySQL backtick quoting
        assertTrue(s.createSQLRenameTimestampColumn().contains("`timestamp`"));
        assertTrue(s.createSQLRenameTimestampColumn().contains(LEASE_TS_COL));
        assertTrue(s.createSQLSelectCurrentTimestampMillis().contains("UNIX_TIMESTAMP"));
    }


    /**
     * Test OracleLeaderElectionDatabaseStructure: VARCHAR2, no index, constraint name truncated to fit
     * Oracle's 30-character identifier limit.
     */
    @Test
    public void testOracleStructure() {
        OracleLeaderElectionDatabaseStructure s = new OracleLeaderElectionDatabaseStructure();

        assertTrue(s.createSQLTable().contains("VARCHAR2(36)"));
        assertTrue(s.createSQLTable().contains("NUMBER(19)"));
        assertTrue(s.createSQLTable().contains("CONSTRAINT pk_"));
        assertNotNull(s.createSQLIndex());
        assertTrue(s.createSQLIndex().contains(INSTANCE_COL));
        assertTrue(s.createSQLUpdateLeaderTimestamp().contains(DEFAULT_TABLE));
        assertTrue(s.createSQLUpdateLeaderTimestamp().contains(FORCE_RELEASE_DEADLINE_COL + " IS NULL"));
        assertTrue(!s.createSQLUpdateLeaderTimestamp().endsWith(SEMICOLON));
        assertTrue(s.createSQLSelectCurrentTimestampMillis().contains("FROM DUAL"));
        // Oracle uses NUMBER(19) for force_release_deadline too
        assertTrue(s.createSQLAddForceReleaseDeadlineColumn().contains("NUMBER(19)"));
        assertTrue(!s.createSQLAddForceReleaseDeadlineColumn().endsWith(SEMICOLON));
        // Oracle inherits standard RENAME COLUMN (works in Oracle 9i+)
        assertTrue(s.createSQLRenameTimestampColumn().toUpperCase().contains("RENAME COLUMN"));
        assertTrue(s.createSQLRenameTimestampColumn().contains(LEASE_TS_COL));
        assertFalse(s.createSQLRenameTimestampColumn().contains("`"));
    }


    /**
     * A schema-qualified table name gives a valid constraint name: without the schema and without the dot.
     */
    @Test
    public void testConstraintNameOfSchemaQualifiedTable() {
        for (JDBCLeaderElectionDatabaseStructure s : new JDBCLeaderElectionDatabaseStructure[] {
            new MySQLLeaderElectionDatabaseStructure(), new SQLServerLeaderElectionDatabaseStructure(), new OracleLeaderElectionDatabaseStructure()}) {
            s.setTableName("myschema.LeaderElection");
            String sql = s.createSQLTable();
            assertTrue(sql.startsWith("CREATE TABLE myschema.LeaderElection"), sql);
            assertTrue(sql.contains("CONSTRAINT pk_leaderelection "), s.getClass().getSimpleName() + ": " + sql);
        }
    }


    /**
     * Test that the Oracle constraint name never exceeds 30 characters even for a long table name.
     */
    @Test
    public void testOracleConstraintNameTruncation() {
        OracleLeaderElectionDatabaseStructure s = new OracleLeaderElectionDatabaseStructure();
        s.setTableName("AVeryLongTableNameThatExceedsThirtyCharactersLimit");
        String sql = s.createSQLTable();
        int pkIdx = sql.indexOf("pk_");
        int endIdx = sql.indexOf(" ", pkIdx);
        String constraintName = sql.substring(pkIdx, endIdx);
        assertTrue(constraintName.length() <= 30,
                "Oracle constraint name must not exceed 30 characters, but was: " + constraintName);
    }


    /**
     * Test that the Oracle instance index name never exceeds 30 characters even for a long table name.
     */
    @Test
    public void testOracleIndexNameTruncation() {
        OracleLeaderElectionDatabaseStructure s = new OracleLeaderElectionDatabaseStructure();
        s.setTableName("AVeryLongTableNameThatExceedsThirtyCharactersLimit");
        String sql = s.createSQLIndex();
        assertNotNull(sql);
        int idxIdx = sql.indexOf("idx_");
        int endIdx = sql.indexOf(" ", idxIdx);
        String indexName = sql.substring(idxIdx, endIdx);
        assertTrue(indexName.length() <= 30,
                "Oracle index name must not exceed 30 characters, but was: " + indexName);
        assertTrue(sql.contains(INSTANCE_COL), "Index must be on the instance column");
    }


    /**
     * Test SQLServerLeaderElectionDatabaseStructure: nvarchar type.
     */
    @Test
    public void testSQLServerStructure() {
        SQLServerLeaderElectionDatabaseStructure s = new SQLServerLeaderElectionDatabaseStructure();

        assertTrue(s.createSQLTable().contains(DEFAULT_TABLE));
        assertNotNull(s.createSQLIndex());
        assertTrue(s.createSQLIndex().contains(INSTANCE_COL));
        assertTrue(s.createSQLUpdateLeaderTimestamp().contains(DEFAULT_TABLE));
        assertTrue(!s.createSQLUpdateLeaderTimestamp().endsWith(SEMICOLON));
        // SQL Server uses sp_rename instead of RENAME COLUMN
        assertTrue(s.createSQLRenameTimestampColumn().contains("sp_rename"));
        assertTrue(s.createSQLRenameTimestampColumn().contains("[timestamp]"));
        assertTrue(s.createSQLRenameTimestampColumn().contains(LEASE_TS_COL));
        // Epoch millis computed from UTC on the server: CURRENT_TIMESTAMP is zone-less local time
        assertTrue(s.createSQLSelectCurrentTimestampMillis().contains("SYSUTCDATETIME"));
    }


    /**
     * Test that the database structure is null by default (auto-detected on first connection),
     * and that JDBCLeaderElectionDatabaseStructure, the auto-detected default for generic JDBC databases,
     * does not emit trailing semicolons that would fail on most JDBC drivers.
     */
    @Test
    public void testDefaultConfigurationUsesJdbcStructureWithoutSemicolons() {
        // Structure is null until the DAO connects and auto-detects the dialect
        DatabaseLeaderElectionConfiguration config = new DatabaseLeaderElectionConfiguration();
        assertNull(config.getLeaderElectionDatabaseStructure(),
                "Structure must be null before first connection (auto-detected by the DAO on init)");

        // Verify JDBCLeaderElectionDatabaseStructure, the fallback auto-detected for H2/PostgreSQL/ANSI-SQL,
        // does not emit trailing semicolons (the base class does, which breaks most JDBC drivers)
        JDBCLeaderElectionDatabaseStructure s = new JDBCLeaderElectionDatabaseStructure();
        assertTrue(!s.createSQLTable().endsWith(SEMICOLON), "Default SQL must not end with semicolon");
        assertTrue(!s.createSQLInsertLeader().endsWith(SEMICOLON), "Default SQL must not end with semicolon");
    }


    /**
     * Test that a caller-supplied structure is not overwritten by validate(), the unconditional
     * assignment bug that silently reverted any configured structure to the base class default.
     */
    @Test
    public void testCallerSuppliedStructureIsPreserved() {
        DatabaseLeaderElectionConfiguration config = new DatabaseLeaderElectionConfiguration();
        OracleLeaderElectionDatabaseStructure oracle = new OracleLeaderElectionDatabaseStructure();
        config.setLeaderElectionDatabaseStructure(oracle);
        assertSame(oracle, config.getLeaderElectionDatabaseStructure(),
                "Caller-supplied structure must not be overwritten by validate()");
    }


    /**
     * Test that table name can be changed and all SQL reflects the new name.
     */
    @Test
    public void testCustomTableName() {
        JDBCLeaderElectionDatabaseStructure s = new JDBCLeaderElectionDatabaseStructure();
        s.setTableName(CUSTOM_TABLE);

        assertTrue(s.createSQLTable().contains(CUSTOM_TABLE));
        assertNotNull(s.createSQLIndex());
        assertTrue(s.createSQLIndex().contains(CUSTOM_TABLE));
        assertTrue(s.createSQLIndex().contains(INSTANCE_COL));
        assertTrue(s.createSQLDropTable().contains(CUSTOM_TABLE));
        assertTrue(s.createSQLInsertLeader().contains(CUSTOM_TABLE));
        assertTrue(s.createSQLDeleteLeaderByName().contains(CUSTOM_TABLE));
        assertTrue(s.createSQLDeleteLeaderByNameAndInstance().contains(CUSTOM_TABLE));
        assertTrue(s.createSQLDeleteLeaderByInstance().contains(CUSTOM_TABLE));
        assertTrue(s.createSQLSelectLeaderByName().contains(CUSTOM_TABLE));
        assertTrue(s.createSQLUpdateLeaderTimestamp().contains(CUSTOM_TABLE));
        assertTrue(s.createSQLStealLeadership().contains(CUSTOM_TABLE));
        assertTrue(s.createSQLSetForceReleaseDeadline().contains(CUSTOM_TABLE));
        assertTrue(s.createSQLAddForceReleaseDeadlineColumn().contains(CUSTOM_TABLE));
        assertTrue(s.createSQLRenameTimestampColumn().contains(CUSTOM_TABLE));
        assertTrue(s.createSQLSelectAll().contains(CUSTOM_TABLE));
    }


    /**
     * Test that setTableName() validates its input and rejects null, blank, and SQL-unsafe names.
     */
    @Test
    public void testSetTableNameValidation() {
        JDBCLeaderElectionDatabaseStructure s = new JDBCLeaderElectionDatabaseStructure();

        // null and blank must throw
        assertThrows(IllegalArgumentException.class, () -> s.setTableName(null), "null must throw");
        assertThrows(IllegalArgumentException.class, () -> s.setTableName(""), "empty must throw");
        assertThrows(IllegalArgumentException.class, () -> s.setTableName("   "), "blank must throw");

        // Names starting with a digit must throw
        assertThrows(IllegalArgumentException.class, () -> s.setTableName("1invalid"), "digit start must throw");

        // Names with SQL-unsafe characters must throw
        assertThrows(IllegalArgumentException.class, () -> s.setTableName("bad-name"), "hyphen must throw");
        assertThrows(IllegalArgumentException.class, () -> s.setTableName("bad name"), "space must throw");
        assertThrows(IllegalArgumentException.class, () -> s.setTableName("; DROP TABLE"), "semicolon must throw");
        assertThrows(IllegalArgumentException.class, () -> s.setTableName("\"quoted\""), "double-quote must throw");
        assertThrows(IllegalArgumentException.class, () -> s.setTableName("a'b"), "single-quote must throw");

        // Valid names must not throw
        assertDoesNotThrow(() -> s.setTableName("ValidTable"));
        assertDoesNotThrow(() -> s.setTableName("valid_table"));
        assertDoesNotThrow(() -> s.setTableName("_private"));
        assertDoesNotThrow(() -> s.setTableName("schema.Table"));
        assertDoesNotThrow(() -> s.setTableName("Table$1"));
    }


    /**
     * Test that getExpectedColumnNames() returns exactly the five required columns.
     */
    @Test
    public void testGetExpectedColumnNames() {
        JDBCLeaderElectionDatabaseStructure s = new JDBCLeaderElectionDatabaseStructure();

        java.util.Set<String> cols = s.getExpectedColumnNames();
        assertNotNull(cols);
        assertTrue(cols.contains("id"));
        assertTrue(cols.contains("name"));
        assertTrue(cols.contains(INSTANCE_COL));
        assertTrue(cols.contains("username"));
        assertTrue(cols.contains(LEASE_TS_COL));
        assertTrue(cols.contains(FORCE_RELEASE_DEADLINE_COL));
        assertFalse(cols.contains("timestamp"), "Legacy column name must not appear in expected set");
        assertTrue(cols.size() == 6, "Exactly 6 columns expected");
    }
}
