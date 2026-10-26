/*
 * JDBCUtilTest.java
 *
 * Copyright by toolarium, all rights reserved.
 */
package com.github.toolarium.leader.election.impl.database.dao;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.StringReader;
import java.lang.reflect.Proxy;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.SQLWarning;
import java.sql.Statement;
import java.util.concurrent.atomic.AtomicInteger;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;


/**
 * Tests the JDBC helpers: binding and reading the supported column types, and closing statements and connections.
 *
 * @author patrick
 */
public class JDBCUtilTest {

    /**
     * Every supported value type is bound and read back with the matching Java type.
     *
     * @throws Exception in case of an error
     */
    @Test
    public void testSetAndGetValues() throws Exception {
        java.sql.Date date = java.sql.Date.valueOf("2026-10-25");
        java.sql.Time time = java.sql.Time.valueOf("11:12:13");
        java.sql.Timestamp timestamp = java.sql.Timestamp.valueOf("2026-10-25 11:12:13");
        byte[] bytes = new byte[] {1, 2, 3};

        try (Connection connection = createDataSource("jdbcUtilValues").getConnection()) {
            try (Statement statement = connection.createStatement()) {
                statement.execute("CREATE TABLE jdbc_types (b BOOLEAN, ti TINYINT, si SMALLINT, i INTEGER, bi BIGINT, r REAL, d DOUBLE PRECISION, "
                                  + "dt DATE, tm TIME, ts TIMESTAMP, n NUMERIC(10,2), v VARCHAR(20), c CHAR(3), cl CLOB, bl BLOB, "
                                  + "a INTEGER ARRAY, nv VARCHAR(20), nn VARCHAR(20))");
            }
            try (PreparedStatement statement = connection.prepareStatement("INSERT INTO jdbc_types VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)")) {
                JDBCUtil.getInstance().setValues(statement, new Object[] {Boolean.TRUE, 1, 2, 3, 4L, 5.5f, 6.5d, date, time, timestamp, new BigDecimal("7.25"),
                                                                          "text", "abc", "clob text", bytes, new Integer[] {1, 2}, null, NullType.VARCHAR});
                assertEquals(1, statement.executeUpdate());
            }

            try (Statement statement = connection.createStatement(); ResultSet rs = statement.executeQuery("SELECT * FROM jdbc_types")) {
                assertTrue(rs.next());
                Object[] values = JDBCUtil.getInstance().getValues(rs);
                assertEquals(Boolean.TRUE, values[0]);
                assertEquals(Byte.valueOf((byte) 1), values[1]);
                assertEquals(Short.valueOf((short) 2), values[2]);
                assertEquals(Integer.valueOf(3), values[3]);
                assertEquals(Long.valueOf(4L), values[4]);
                assertEquals(Float.valueOf(5.5f), values[5]);
                assertEquals(Double.valueOf(6.5d), values[6]);
                assertEquals(date, values[7]);
                assertEquals(time, values[8]);
                assertEquals(new java.util.Date(timestamp.getTime()), values[9]);
                assertEquals(new BigDecimal("7.25"), values[10]);
                assertEquals("text", values[11]);
                assertEquals("abc", values[12]);
                assertEquals("clob text", values[13]);
                assertArrayEquals(bytes, (byte[]) values[14]);
                assertTrue(String.valueOf(values[15]).contains("1"), "Other types are read as string: " + values[15]);
                assertEquals("", values[16], "A null string is read as empty string");
                assertEquals("", values[17]);
            }
        }
    }


    /**
     * A missing statement is rejected; missing values bind nothing.
     *
     * @throws Exception in case of an error
     */
    @Test
    public void testSetValuesArguments() throws Exception {
        assertThrows(IllegalArgumentException.class, () -> JDBCUtil.getInstance().setValues(null, new Object[] {"x"}));
        try (Connection connection = createDataSource("jdbcUtilArguments").getConnection();
             PreparedStatement statement = connection.prepareStatement("SELECT 1")) {
            assertEquals(statement, JDBCUtil.getInstance().setValues(statement, null));
        }
    }


    /**
     * A reader is read completely; a missing reader gives null.
     *
     * @throws Exception in case of an error
     */
    @Test
    public void testReadString() throws Exception {
        assertNull(JDBCUtil.getInstance().readString(null));
        assertEquals("some text", JDBCUtil.getInstance().readString(new StringReader("some text")));
    }


    /**
     * Statements and connections are closed; null is accepted, warnings are logged and errors are swallowed.
     *
     * @throws Exception in case of an error
     */
    @Test
    public void testClose() throws Exception {
        JDBCUtil.getInstance().closeStmnt(null);
        JDBCUtil.getInstance().closeConn(null);

        Connection connection = createDataSource("jdbcUtilClose").getConnection();
        Statement statement = connection.createStatement();
        JDBCUtil.getInstance().closeStmnt(statement);
        assertTrue(statement.isClosed());
        JDBCUtil.getInstance().closeConn(connection);
        assertTrue(connection.isClosed());

        AtomicInteger closeCalls = new AtomicInteger();
        JDBCUtil.getInstance().closeStmnt(failingResource(Statement.class, true, closeCalls));
        JDBCUtil.getInstance().closeStmnt(failingResource(Statement.class, false, closeCalls));
        JDBCUtil.getInstance().closeConn(failingResource(Connection.class, true, closeCalls));
        JDBCUtil.getInstance().closeConn(failingResource(Connection.class, false, closeCalls));
        assertEquals(4, closeCalls.get(), "close() must be called even when the warnings cannot be read");
    }


    /**
     * Create a H2 data source.
     *
     * @param dbName the database name
     * @return the data source
     */
    private static JdbcDataSource createDataSource(String dbName) {
        JdbcDataSource ds = new JdbcDataSource();
        ds.setURL("jdbc:h2:mem:" + dbName + ";DB_CLOSE_DELAY=-1");
        ds.setUser("sa");
        ds.setPassword("");
        return ds;
    }


    /**
     * Create a statement or connection that reports two warnings, or fails to read them, and fails to close.
     *
     * @param <T> the resource type
     * @param type the resource type
     * @param withWarnings true to report warnings, false to fail reading them
     * @param closeCalls counts the close calls
     * @return the resource
     */
    private static <T> T failingResource(Class<T> type, boolean withWarnings, AtomicInteger closeCalls) {
        return type.cast(Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[] {type}, (proxy, method, args) -> {
            switch (method.getName()) {
                case "getWarnings":
                    if (!withWarnings) {
                        throw new SQLException("Simulated warning failure");
                    }
                    SQLWarning warning = new SQLWarning("first warning");
                    warning.setNextWarning(new SQLWarning("second warning"));
                    return warning;
                case "clearWarnings":
                    return null;
                case "close":
                    closeCalls.incrementAndGet();
                    throw new SQLException("Simulated close failure");
                default:
                    throw new UnsupportedOperationException(method.getName());
            }
        }));
    }
}
