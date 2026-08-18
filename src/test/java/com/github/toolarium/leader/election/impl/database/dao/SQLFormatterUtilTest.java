/*
 * SQLFormatterUtilTest.java
 *
 * Copyright by toolarium, all rights reserved.
 */
package com.github.toolarium.leader.election.impl.database.dao;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.DataTruncation;
import java.sql.SQLException;
import org.junit.jupiter.api.Test;


/**
 * Test the SQLFormatterUtil.
 *
 * @author patrick
 */
public class SQLFormatterUtilTest {

    /**
     * Test getInstance returns non-null singleton.
     */
    @Test
    public void testGetInstance() {
        assertNotNull(SQLFormatterUtil.getInstance());
    }


    /**
     * Test formatting a basic SQLException.
     */
    @Test
    public void testFormatSQLException() {
        SQLException ex = new SQLException("constraint violation", "23000", 1062);
        String result = SQLFormatterUtil.getInstance().formatSQLException("Insert failed", ex);
        assertTrue(result.contains("Insert failed"));
        assertTrue(result.contains("1062"));
        assertTrue(result.contains("23000"));
        assertTrue(result.contains("constraint violation"));
    }


    /**
     * Test formatting with null header falls back to default message.
     */
    @Test
    public void testFormatSQLExceptionNullHeader() {
        SQLException ex = new SQLException("some error", "42000", 0);
        String result = SQLFormatterUtil.getInstance().formatSQLException(null, ex);
        assertTrue(result.contains("An SQL error occurred"));
        assertTrue(result.contains("some error"));
    }


    /**
     * Test formatting a DataTruncation (subclass of SQLException) includes extra fields.
     */
    @Test
    public void testFormatDataTruncation() {
        DataTruncation dt = new DataTruncation(1, false, true, 100, 50);
        String result = SQLFormatterUtil.getInstance().formatSQLException("Truncation", dt);
        assertTrue(result.contains("Truncation"));
        assertTrue(result.contains("index"));
        assertTrue(result.contains("data size"));
        assertTrue(result.contains("transfer size"));
    }


    /**
     * Test chained exceptions are included in the output.
     */
    @Test
    public void testChainedExceptions() {
        SQLException next = new SQLException("nested error");
        SQLException ex = new SQLException("outer error");
        ex.setNextException(next);
        String result = SQLFormatterUtil.getInstance().formatSQLException("Chain test", ex);
        assertTrue(result.contains("outer error"));
        assertTrue(result.contains("nested error"));
    }
}
