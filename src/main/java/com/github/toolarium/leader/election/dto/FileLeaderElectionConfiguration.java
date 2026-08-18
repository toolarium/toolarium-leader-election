/*
 * FileLeaderElectionConfiguration.java
 *
 * Copyright by toolarium, all rights reserved.
 */
package com.github.toolarium.leader.election.dto;

import java.io.File;
import java.time.Duration;

/**
 * The leader election file configuration
 * 
 * @author patrick
 */
public class FileLeaderElectionConfiguration extends LeaderElectionConfiguration {
    private String basePath = System.getProperty("java.io.tmpdir");

    
    /**
     * Constructor for FileLeaderElectionConfiguration
     * 
     * @throws IllegalArgumentException In case of a parameter failure
     */
    public FileLeaderElectionConfiguration() throws IllegalArgumentException {
        super();
    }


    /**
     * Constructor for FileLeaderElectionConfiguration
     *
     * @param timeoutInSeconds the timeout
     * @throws IllegalArgumentException In case of a parameter failure
     */
    public FileLeaderElectionConfiguration(long timeoutInSeconds) throws IllegalArgumentException {
        super(timeoutInSeconds);
    }

    
    /**
     * Constructor for FileLeaderElectionConfiguration
     *
     * @param timeout the timeout
     * @param renewDeadline the rewnew deadline
     * @param retryPeriod the retry period
     * @throws IllegalArgumentException In case of a parameter failure
     */
    public FileLeaderElectionConfiguration(Duration timeout, Duration renewDeadline, Duration retryPeriod) throws IllegalArgumentException {
        super(timeout, renewDeadline, retryPeriod);
    }

    
    /**
     * Constructor for FileLeaderElectionConfiguration
     * 
     * @param basePath the base path
     * @param timeout the timeout
     * @param renewDeadline the rewnew deadline
     * @param retryPeriod the retry period
     * @throws IllegalArgumentException In case of a parameter failure
     */
    public FileLeaderElectionConfiguration(String basePath, Duration timeout, Duration renewDeadline, Duration retryPeriod) throws IllegalArgumentException {
        super(timeout, renewDeadline, retryPeriod);
        this.basePath = createBasePath(basePath);
    }


    /**
     * @see com.github.toolarium.leader.election.dto.LeaderElectionConfiguration#validate()
     */
    protected void validate() {
        super.validate();
        basePath = createBasePath(basePath);
    }
    
    
    /**
     * Gets the base path
     *
     * @return the base path
     */
    public String getBasePath() {
        return basePath;
    }


    /**
     * Create the base path
     * 
     * @param path input path
     * @return the prepared path
     */
    public static String createBasePath(String path) {
        String p = path;
        if (p == null) {
            p = System.getProperty("java.io.tmpdir");
        }
        
        p = new File(p).getAbsoluteFile().getPath().replace('\\', '/');
        if (!p.endsWith("/")) {
            p += "/";
        }
        
        new File(p).mkdirs();
        return p;
    }
}
