/*
 * NullType.java
 *
 * Copyright by toolarium, all rights reserved.
 */
package com.github.toolarium.leader.election.impl.database.dao;

import java.sql.Types;

/**
 * Holds the type of a SQL null value.
 * 
 * @author patrick
 */
public class NullType {
    /** the JDBC bit type */
    public static final NullType BIT = new NullType(Types.BIT);

    /** the JDBC tinyint type */
    public static final NullType TINYINT = new NullType(Types.TINYINT);

    /** the JDBC smallint type */
    public static final NullType SMALLINT = new NullType(Types.SMALLINT);

    /** the JDBC integer type */
    public static final NullType INTEGER = new NullType(Types.INTEGER);

    /** the JDBC bigint type */
    public static final NullType BIGINT = new NullType(Types.BIGINT);

    /** the JDBC float type */
    public static final NullType FLOAT = new NullType(Types.FLOAT);

    /** the JDBC real type */
    public static final NullType REAL = new NullType(Types.REAL);

    /** the JDBC double type */
    public static final NullType DOUBLE = new NullType(Types.DOUBLE);

    /** the JDBC numeric type */
    public static final NullType NUMERIC = new NullType(Types.NUMERIC);

    /** the JDBC decimal type */
    public static final NullType DECIMAL = new NullType(Types.DECIMAL);

    /** the JDBC char type */
    public static final NullType CHAR = new NullType(Types.CHAR);

    /** the JDBC varchar type */
    public static final NullType VARCHAR = new NullType(Types.VARCHAR);

    /** the JDBC longvarchar type */
    public static final NullType LONGVARCHAR = new NullType(Types.LONGVARCHAR);

    /** the JDBC date type */
    public static final NullType DATE = new NullType(Types.DATE);

    /** the JDBC time type */
    public static final NullType TIME = new NullType(Types.TIME);

    /** the JDBC timestamp type */
    public static final NullType TIMESTAMP = new NullType(Types.TIMESTAMP);

    /** the JDBC binary type */
    public static final NullType BINARY = new NullType(Types.BINARY);

    /** the JDBC varbinary type */
    public static final NullType VARBINARY = new NullType(Types.VARBINARY);

    /** the JDBC longvarbinary type */
    public static final NullType LONGVARBINARY = new NullType(Types.LONGVARBINARY);

    /** the JDBC null type */
    public static final NullType NULL = new NullType(Types.NULL);

    /** the JDBC other type */
    public static final NullType OTHER = new NullType(Types.OTHER);

    /** the JDBC java object type */
    public static final NullType JAVA_OBJECT = new NullType(Types.JAVA_OBJECT);

    /** the JDBC distinct type */
    public static final NullType DISTINCT = new NullType(Types.DISTINCT);

    /** the JDBC struct type */
    public static final NullType STRUCT = new NullType(Types.STRUCT);

    /** the JDBC array type */
    public static final NullType ARRAY = new NullType(Types.ARRAY);

    /** the JDBC blob type */
    public static final NullType BLOB = new NullType(Types.BLOB);

    /** the JDBC clob type */
    public static final NullType CLOB = new NullType(Types.CLOB);

    /** the JDBC ref type */
    public static final NullType REF = new NullType(Types.REF);

    /** the JDBC datalink type */
    public static final NullType DATALINK = new NullType(Types.DATALINK);

    /** the JDBC boolean type */
    public static final NullType BOOLEAN = new NullType(Types.BOOLEAN);
    
    private int type;

    
    /**
     * Constructor
     * @param type The type
     */
    public NullType(int type) {
        this.type = type;
    }

    
    /**
     * Gets the type of the current field.
     * @return The type
     */
    public int getType() {
        return type;
    }    
   
    
    /**
     * @see java.lang.Object#toString()
     */
    @Override
    public String toString() {
        return "null(" + type + ")";
    }
}
