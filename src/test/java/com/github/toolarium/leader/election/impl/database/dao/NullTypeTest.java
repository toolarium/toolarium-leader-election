/*
 * NullTypeTest.java
 *
 * Copyright by toolarium, all rights reserved.
 */
package com.github.toolarium.leader.election.impl.database.dao;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.Types;
import org.junit.jupiter.api.Test;


/**
 * Test the NullType.
 *
 * @author patrick
 */
public class NullTypeTest {

    /**
     * Test constants map to correct JDBC types.
     */
    @Test
    public void testConstants() {
        assertEquals(Types.INTEGER, NullType.INTEGER.getType());
        assertEquals(Types.VARCHAR, NullType.VARCHAR.getType());
        assertEquals(Types.DECIMAL, NullType.DECIMAL.getType());
        assertEquals(Types.TIMESTAMP, NullType.TIMESTAMP.getType());
        assertEquals(Types.BOOLEAN, NullType.BOOLEAN.getType());
        assertEquals(Types.NULL, NullType.NULL.getType());
    }


    /**
     * Test custom type constructor.
     */
    @Test
    public void testCustomType() {
        NullType custom = new NullType(Types.CLOB);
        assertEquals(Types.CLOB, custom.getType());
    }


    /**
     * Test toString format.
     */
    @Test
    public void testToString() {
        String s = NullType.INTEGER.toString();
        assertTrue(s.startsWith("null("));
        assertTrue(s.endsWith(")"));
        assertEquals("null(" + Types.INTEGER + ")", s);
    }
}
