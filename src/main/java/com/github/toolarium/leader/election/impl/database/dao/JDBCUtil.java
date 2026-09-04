/*
 * JDBCUtil.java
 *
 * Copyright by toolarium, all rights reserved.
 */
package com.github.toolarium.leader.election.impl.database.dao;

import java.io.BufferedInputStream;
import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.Reader;
import java.io.StringReader;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.SQLWarning;
import java.sql.Statement;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.format.DateTimeFormatter;
import java.util.Date;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


/**
 * JDBC utitliy
 * 
 * @author patrick
 */
public final class JDBCUtil {
    private static final String SET_DATA_TO_COLUMN = "Set data to column '";
    private static final Logger LOG = LoggerFactory.getLogger(JDBCUtil.class);

    
    /**
     * Private class, the only instance of the singelton which will be created by accessing the holder class.
     *
     * @author patrick
     */
    private static final class HOLDER {
        static final JDBCUtil INSTANCE = new JDBCUtil();
    }
    

    /**
     * Constructor
     */
    private JDBCUtil() {
        // NOP
    }

    
    /**
     * Get the instance
     *
     * @return the instance
     */
    public static JDBCUtil getInstance() {
        return HOLDER.INSTANCE;
    }

    
    /**
     * Close the statement, can handle null statements.
     * 
     * @param stmnt the statement to close
     */
    public void closeStmnt(Statement stmnt) {
        if (stmnt != null) {
            try {
                // log sql warnings
                SQLWarning warnings = stmnt.getWarnings();
                while (warnings != null) {
                    LOG.info(SQLFormatterUtil.getInstance().formatSQLException("A SQL warning occurred", warnings));
                    warnings = warnings.getNextWarning();
                }

                // clear warnings
                stmnt.clearWarnings();
            } catch (SQLException e) {
                LOG.warn(SQLFormatterUtil.getInstance().formatSQLException("Could not print and clear SQL warnings", e));
            }

            try {
                stmnt.close();
                if (LOG.isDebugEnabled()) {
                    LOG.debug("Statement closed.");
                }
            } catch (SQLException e) {
                // swallow exception. Since we're closing it we'll let it be dead.
                if (LOG.isDebugEnabled()) {
                    LOG.debug("Could not close statement!");
                }
            }
        }
    }
    
    
    /**
     * Close the connection, can handle null connections.
     * 
     * @param conn the connection to close
     */
    public void closeConn(Connection conn) {
        if (conn != null) {
            try {
                try {
                    // log sql warnings
                    SQLWarning warnings = conn.getWarnings();
                    while (warnings != null) {
                        LOG.warn(SQLFormatterUtil.getInstance().formatSQLException("A SQL warning occurred", warnings));
                        warnings = warnings.getNextWarning();
                    }

                    // clear warnings
                    conn.clearWarnings();
                } catch (SQLException e) {
                    LOG.warn(SQLFormatterUtil.getInstance().formatSQLException("Could not print and clear SQL warnings", e));
                }

                conn.close();
                if (LOG.isDebugEnabled()) {
                    LOG.debug("Closed connection.");
                }
            } catch (SQLException e) {
                // swallow exception. Since we're closing it we'll let it be dead.
                if (LOG.isDebugEnabled()) {
                    LOG.debug("Could not close connection!");
                }
            }
        }
    }    
    
    /**
     * Executes a PreparedStatement by using the given values and process the result.
     * 
     * @param statement The PreparedStatement
     * @param values The values of the sql statement
     * @return the returned values of the database response as objects.
     * @exception SQLException in case of error
     * @throws IllegalArgumentException In case of invalid parameters
     */
    public PreparedStatement setValues(PreparedStatement statement, Object[] values) throws SQLException {
        Object value;
        PreparedStatement stmnt = statement;
        
        if (stmnt == null) {
            throw new IllegalArgumentException("Invalid PreparedStatement!");
        }

        if (values != null) {
            for (int i = 0; i < values.length; i++) {
                value = values[ i ];
                
                if (value == null) {
                    if (LOG.isDebugEnabled()) {
                        LOG.debug("Given value is null '" + i + "'!");
                    }

                    stmnt.setNull(i + 1, NullType.VARCHAR.getType());
                } else if (value instanceof NullType) {
                    int type = ((NullType)value).getType();

                    if (LOG.isDebugEnabled()) {
                        LOG.debug(SET_DATA_TO_COLUMN + i + "': [" + value + "], type: " + type);
                    }

                    stmnt.setNull(i + 1, type);
                } else if (value instanceof byte[]) {
                    if (LOG.isDebugEnabled()) {
                        LOG.debug(SET_DATA_TO_COLUMN + i + "':\n" + byteArrayToHex((byte[])value));
                    }

                    ByteArrayInputStream inputStream = new ByteArrayInputStream((byte[])value);
                    stmnt.setBlob(i + 1, inputStream);
                } else if (value instanceof Date) {
                    if (LOG.isDebugEnabled()) {  
                        LOG.debug(SET_DATA_TO_COLUMN + i + "': [" + DateTimeFormatter.ISO_INSTANT.format(((Date)value).toInstant()) + "]");
                    }
                                            
                    stmnt.setObject(i + 1, value);
                } else if (value instanceof CharSequence) {
                    boolean isClob = false;
                    try {
                        java.sql.ResultSetMetaData meta = stmnt.getMetaData();
                        isClob = meta != null && meta.getColumnType(i + 1) == Types.CLOB;
                    } catch (SQLException e) {
                        // some drivers (e.g. MariaDB 3.x) throw when getMetaData() is called on non-SELECT statements
                    }
                    if (LOG.isDebugEnabled()) {
                        LOG.debug(SET_DATA_TO_COLUMN + i + "': [" + value + "] (" + isClob + ")");
                    }

                    if (isClob) {
                        stmnt.setClob(i + 1, new StringReader(((CharSequence) value).toString()));
                    } else {
                        stmnt.setObject(i + 1, value);
                    }
                } else if (value instanceof Integer || value instanceof Long
                         || value instanceof Float || value instanceof Double
                         || value instanceof Boolean
                         || value instanceof java.math.BigInteger || value instanceof java.math.BigDecimal) {
                    if (LOG.isDebugEnabled()) {
                        LOG.debug(SET_DATA_TO_COLUMN + i + "': [" + value + "]");
                    }
                                            
                    stmnt.setObject(i + 1, value);
                } else {
                    if (LOG.isDebugEnabled()) {                 
                        LOG.debug(SET_DATA_TO_COLUMN + i + "':\n" + value);
                    }

                    stmnt.setObject(i + 1, value);
                }
            }
        }
        
        return stmnt;
    }

       
    /**
     * Get values
     * 
     * @param resultSet the result set
     * @return the values
     * @throws SQLException in case of error
     */
    public Object[] getValues(ResultSet resultSet) throws SQLException  {
        java.sql.ResultSetMetaData meta = resultSet.getMetaData();
        int columnCount = meta.getColumnCount();
        Object[] result = new Object[columnCount];
        for (int idx = 1; idx <= columnCount; idx++) {
            switch (meta.getColumnType(idx)) {
                case Types.BIT:
                case Types.BOOLEAN:
                    result[idx - 1] = Boolean.valueOf(resultSet.getBoolean(idx));
                    break;
                    
                case Types.TINYINT:
                    result[idx - 1] = Byte.valueOf(resultSet.getByte(idx));
                    break;
    
                case Types.SMALLINT:
                    result[idx - 1] = Short.valueOf(resultSet.getShort(idx));
                    break;
    
                case Types.INTEGER:
                    result[idx - 1] = Integer.valueOf(resultSet.getInt(idx));
                    break;
    
                case Types.BIGINT:
                    result[idx - 1] = Long.valueOf(resultSet.getLong(idx));
                    break;
    
                case Types.REAL:
                    result[idx - 1] = Float.valueOf(resultSet.getFloat(idx));
                    break;
    
                case Types.FLOAT:
                case Types.DOUBLE:
                    result[idx - 1] = Double.valueOf(resultSet.getDouble(idx));
                    break;
    
                case Types.DATE:
                    result[idx - 1] = resultSet.getDate(idx);
                    break;
    
                case Types.TIME:
                    result[idx - 1] = resultSet.getTime(idx);
                    break;
    
                case Types.TIMESTAMP:
                    result[idx - 1] = getDate(resultSet.getTimestamp(idx));
                    break;
    
                case Types.NUMERIC:
                case Types.DECIMAL:
                    result[idx - 1] = resultSet.getBigDecimal(idx);
                    break;
    
                case Types.VARCHAR:
                case Types.CHAR:
                case Types.LONGVARCHAR:
                    String d = resultSet.getString(idx);
                    if (d == null || resultSet.wasNull()) {
                        d = "";
                    }
                    result[ idx - 1 ] = d;
                    break;
    
                case Types.NVARCHAR:
                case Types.NCHAR:
                    d = resultSet.getNString(idx);
                    if (d == null || resultSet.wasNull()) {
                        d = "";
                    }
                    result[ idx - 1 ] = d;
                    break;
    
                case Types.CLOB:
                    result[idx - 1] = readString(resultSet.getClob(idx).getCharacterStream());
                    break;
                    
                case Types.NCLOB:
                    result[idx - 1] = readString(resultSet.getNClob(idx).getCharacterStream());
                    break;
    
                case Types.BINARY:
                case Types.VARBINARY:
                case Types.LONGVARBINARY:
                case Types.BLOB:
                    result[ idx - 1 ] = readByteArray(resultSet.getBlob(idx).getBinaryStream());
                    break;
    
                case Types.JAVA_OBJECT:
                    result[idx - 1] = resultSet.getObject(idx);
                    break;
    
                default:
                    d = resultSet.getString(idx);
                    if (d == null || resultSet.wasNull()) {
                        d = "";
                    }
    
                    result[ idx - 1 ] = d;
            }
        }
        
        return result;
    }

    
    /**
     * Read from a given reader
     * 
     * @param reader the reader
     * @return the string
     * @throws SQLException in case of error
     */
    public String readString(Reader reader) throws SQLException {
        if (reader == null) {
            return null;
        }

        StringBuilder sb = new StringBuilder();

        try {
            final BufferedReader br = new BufferedReader(reader);

            int b = -1;
            while ((b = br.read()) != -1) {
                sb.append((char) b);
            }

            br.close();
        } catch (IOException e) {
            throw new SQLException("Could not read clob field!", e);
        }

        return sb.toString();
    }

    
    /**
     * Read from a given stream
     *
     * @param stream the stream to read
     * @return the byte array
     * @throws SQLException in case of error
     */
    private byte[] readByteArray(InputStream stream) throws SQLException {
        if (stream == null) {
            return null;
        }
        
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        InputStream inputStream = new BufferedInputStream(stream);
        byte[] tempBuffer = new byte[64000];

        int b = -1;
        try {
            while ((b = inputStream.read(tempBuffer)) != -1) {
                buf.write(tempBuffer, 0, b);
            }

            inputStream.close();
        } catch (IOException e) {
            throw new SQLException("Could not read blob field!", e);
        }

        return buf.toByteArray();
    }

    
    /**
     * Creates and returns a Date instance with the value of the given TimeStamp.
     * 
     * @param timeStamp The Timestamp which value will be used to create the Date returned. 
     * @return The Date.
     */
    private static Date getDate(Timestamp timeStamp) {
        Date date = null;

        if (timeStamp != null) {
            date = new Date(timeStamp.getTime());
        }

        return date;
    }
    
    
    /**
     * Byte array into hex
     *
     * @param a the byte array
     * @return the hex representation 
     */
    private String byteArrayToHex(byte[] a) {
        StringBuilder sb = new StringBuilder(a.length * 2);
        for (byte b : a) {
            sb.append(String.format("%02x", b));
        }

        return sb.toString();
    }
}
