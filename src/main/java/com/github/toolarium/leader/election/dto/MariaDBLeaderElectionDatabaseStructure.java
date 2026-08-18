/*
 * MariaDBLeaderElectionDatabaseStructure.java
 *
 * Copyright by toolarium, all rights reserved.
 */
package com.github.toolarium.leader.election.dto;


/**
 * Defines the MariaDB database structure. MariaDB is syntax-compatible with MySQL;
 * this class exists for explicit typing and extends {@link MySQLLeaderElectionDatabaseStructure}.
 *
 * @author patrick
 */
public class MariaDBLeaderElectionDatabaseStructure extends MySQLLeaderElectionDatabaseStructure {

    /**
     * Constructor for MariaDBLeaderElectionDatabaseStructure
     */
    public MariaDBLeaderElectionDatabaseStructure() {
        super();
    }
}
