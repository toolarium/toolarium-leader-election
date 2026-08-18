/*
 * SQLFormatter.java
 *
 * Copyright by toolarium, all rights reserved.
 */
package com.github.toolarium.leader.election.impl.database.dao;

import java.sql.DataTruncation;
import java.sql.SQLException;


/**
 * The SQLFormatter utility
 * 
 * @author patrick
 */
public final class SQLFormatterUtil {
    private static final String MSG_START = " (";
    private static final String MSG_END = "): ";
    private static final String MSG_ATTR_START = " '";
    private static final String MSG_ATTR_END = "'";
    private static final String MSG_SEPARATOR = " / ";
    private static final String MSG_NEWLINE = "\n";
    private static final String MSG_INDENT = "    ";

    
    /**
     * Private class, the only instance of the singelton which will be created by accessing the holder class.
     *
     * @author patrick
     */
    private static final class HOLDER {
        static final SQLFormatterUtil INSTANCE = new SQLFormatterUtil();
    }

    /**
     * Constructor
     */
    private SQLFormatterUtil() {
        // NOP
    }

    /**
     * Get the instance
     *
     * @return the instance
     */
    public static SQLFormatterUtil getInstance() {
        return HOLDER.INSTANCE;
    }
    

    /**
     * Formats an SQL exception to a well format string. The idea is to have the same messages on errors.
     * 
     * @param header the header of the message
     * @param exception the SQL exception
     * @return the database pool manager
     */
    public String formatSQLException(String header, SQLException exception) {
        StringBuilder buffer = new StringBuilder();

        // append the exception header message
        appendErrorHeader(buffer, header, exception);

        // append the exception data
        appendExceptionData(buffer, exception);

        // append the exception trailer message
        appendErrorTrailer(buffer, exception);

        return buffer.toString();
    }    

    
    /**
     * Appends exception specific data
     * 
     * @param buffer    the buffer
     * @param exception the exception
     */
    private void appendExceptionData(StringBuilder buffer, SQLException exception) {
        // append( specific
        if (exception instanceof DataTruncation) {
            buffer.append(MSG_SEPARATOR);
            appendAttribute(buffer, "index", "" + ((DataTruncation) exception).getIndex());
            buffer.append(MSG_SEPARATOR);
            appendAttribute(buffer, "parameter", "" + ((DataTruncation) exception).getParameter());
            buffer.append(MSG_SEPARATOR);
            appendAttribute(buffer, "read", "" + ((DataTruncation) exception).getRead());
            buffer.append(MSG_SEPARATOR);
            appendAttribute(buffer, "data size", "" + ((DataTruncation) exception).getDataSize());
            buffer.append(MSG_SEPARATOR);
            appendAttribute(buffer, "transfer size", "" + ((DataTruncation) exception).getTransferSize());
        }
    }    
    
    
    /**
     * Create the error header
     * 
     * @param buffer the buffer
     * @param header the header
     * @param exception the exception
     */
    private void appendErrorHeader(StringBuilder buffer, String header, SQLException exception) {
        String sqlHeader = "An SQL error occurred";

        if (header != null) {
            sqlHeader = header;
        }
        
        buffer.append(sqlHeader);
        buffer.append(MSG_START);
        appendAttribute(buffer, "error code", "" + exception.getErrorCode());
        buffer.append(MSG_SEPARATOR);
        appendAttribute(buffer, "SQL state", "" + exception.getSQLState());
    }

    
    /**
     * Appends the error trailer
     * 
     * @param buffer the buffer
     * @param exception the exception
     */
    private void appendErrorTrailer(StringBuilder buffer, SQLException exception) {
        // append the exception message
        buffer.append(MSG_END);
        buffer.append(exception.getMessage());

        if (exception.getNextException() != null) {
            buffer.append(MSG_NEWLINE + MSG_INDENT);
            buffer.append(formatSQLException(null, exception.getNextException()));
        }
    }

    
    /**
     * Appends an attribute value
     * 
     * @param buffer the buffer
     * @param description the description
     * @param value the value
     */
    private void appendAttribute(StringBuilder buffer, String description, String value) {
        buffer.append(description);
        buffer.append(MSG_ATTR_START);
        buffer.append(value);
        buffer.append(MSG_ATTR_END);
    }
}
