/*
 * FileLeaderElectionConfigurationTest.java
 *
 * Copyright by toolarium, all rights reserved.
 */
package com.github.toolarium.leader.election.dto.file;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.github.toolarium.leader.election.dto.LeaderElectionConfiguration;
import java.time.Duration;
import org.junit.jupiter.api.Test;


/**
 * Unit tests for {@link FileLeaderElectionConfiguration}: constructors, basePath handling,
 * and {@link FileLeaderElectionConfiguration#createBasePath(String)} static utility.
 *
 * @author patrick
 */
public class FileLeaderElectionConfigurationTest {
    private static final String TRAILING_SLASH = "/";


    /**
     * Default constructor must use the system temp directory as basePath and
     * inherit the default timing values from {@link LeaderElectionConfiguration}.
     */
    @Test
    public void testDefaultConstructorBasePathIsSystemTempDir() {
        FileLeaderElectionConfiguration config = new FileLeaderElectionConfiguration();
        String expected = FileLeaderElectionConfiguration.createBasePath(null);
        assertEquals(expected, config.getBasePath(),
                "Default basePath must equal createBasePath(null) (the system temp dir)");
    }


    /**
     * Default constructor must inherit the timing defaults from the base class.
     */
    @Test
    public void testDefaultConstructorInheritsTimingDefaults() {
        FileLeaderElectionConfiguration config = new FileLeaderElectionConfiguration();
        LeaderElectionConfiguration defaults = new LeaderElectionConfiguration();
        assertEquals(defaults.getTimeout(), config.getTimeout());
        assertEquals(defaults.getRenewDeadline(), config.getRenewDeadline());
        assertEquals(defaults.getRetryPeriod(), config.getRetryPeriod());
    }


    /**
     * Timeout-only constructor must set the timeout and leave basePath as the system temp dir.
     */
    @Test
    public void testTimeoutConstructorSetsTimeoutAndDefaultBasePath() {
        FileLeaderElectionConfiguration config = new FileLeaderElectionConfiguration(5);
        assertEquals(Duration.ofSeconds(5), config.getTimeout());
        assertNotNull(config.getBasePath(), "basePath must not be null");
        assertTrue(config.getBasePath().endsWith(TRAILING_SLASH),
                "basePath must end with '/' when using timeout constructor");
    }


    /**
     * Duration constructor must propagate all three timing values and keep default basePath.
     */
    @Test
    public void testDurationConstructorPropagatesTimings() {
        Duration timeout = Duration.ofSeconds(20);
        Duration renewDeadline = Duration.ofSeconds(10);
        Duration retryPeriod = Duration.ofSeconds(5);
        FileLeaderElectionConfiguration config = new FileLeaderElectionConfiguration(timeout, renewDeadline, retryPeriod);
        assertEquals(timeout, config.getTimeout());
        assertEquals(renewDeadline, config.getRenewDeadline());
        assertEquals(retryPeriod, config.getRetryPeriod());
        assertNotNull(config.getBasePath());
    }


    /**
     * Four-argument constructor must set basePath from the supplied path and all timing values.
     */
    @Test
    public void testBasePathConstructorSetsCustomPath() {
        String customPath = System.getProperty("java.io.tmpdir");
        FileLeaderElectionConfiguration config = new FileLeaderElectionConfiguration(
                customPath,
                Duration.ofSeconds(10), Duration.ofSeconds(6), Duration.ofSeconds(3));
        assertNotNull(config.getBasePath(), "basePath must not be null");
        assertTrue(config.getBasePath().endsWith(TRAILING_SLASH),
                "basePath must end with '/' when set via constructor: " + config.getBasePath());
        assertFalse(config.getBasePath().contains("\\"),
                "basePath must not contain backslashes: " + config.getBasePath());
    }


    /**
     * getBasePath() must always return a path ending with '/'.
     */
    @Test
    public void testBasePathAlwaysEndsWithSlash() {
        FileLeaderElectionConfiguration config = new FileLeaderElectionConfiguration();
        assertTrue(config.getBasePath().endsWith(TRAILING_SLASH),
                "getBasePath() must always end with '/': " + config.getBasePath());
    }


    /**
     * setBasePath() must update the stored path, ensure it ends with '/',
     * and convert backslashes to forward slashes.
     */
    @Test
    public void testSetBasePathUpdatesAndNormalizesValue() {
        FileLeaderElectionConfiguration config = new FileLeaderElectionConfiguration();
        String customPath = System.getProperty("java.io.tmpdir");
        config.setBasePath(customPath);
        assertTrue(config.getBasePath().endsWith(TRAILING_SLASH),
                "basePath must end with '/' after setBasePath: " + config.getBasePath());
        assertFalse(config.getBasePath().contains("\\"),
                "basePath must not contain backslashes after setBasePath: " + config.getBasePath());
    }


    /**
     * setBasePath(null) must fall back to the system temp directory instead of throwing.
     */
    @Test
    public void testSetBasePathNullFallsBackToTmpDir() {
        FileLeaderElectionConfiguration config = new FileLeaderElectionConfiguration();
        config.setBasePath(null);
        String expectedFromTmpDir = FileLeaderElectionConfiguration.createBasePath(null);
        assertEquals(expectedFromTmpDir, config.getBasePath(),
                "setBasePath(null) must fall back to the system temp dir");
    }


    /**
     * createBasePath(null) must return the system temp dir as a forward-slash-terminated path.
     */
    @Test
    public void testCreateBasePathNullReturnsTmpDir() {
        String result = FileLeaderElectionConfiguration.createBasePath(null);
        assertNotNull(result, "createBasePath(null) must not return null");
        assertTrue(result.endsWith(TRAILING_SLASH), "createBasePath(null) must end with '/'");
        assertFalse(result.contains("\\"), "createBasePath(null) must not contain backslashes");
    }


    /**
     * createBasePath(path) must add a trailing '/' when the input has none.
     */
    @Test
    public void testCreateBasePathAddsTrailingSlash() {
        String tmpDir = System.getProperty("java.io.tmpdir").replace('\\', '/');
        String noTrail = tmpDir;
        if (noTrail.endsWith(TRAILING_SLASH)) {
            noTrail = noTrail.substring(0, noTrail.length() - 1);
        }
        String result = FileLeaderElectionConfiguration.createBasePath(noTrail);
        assertTrue(result.endsWith(TRAILING_SLASH), "createBasePath must add a trailing '/' when missing");
    }


    /**
     * createBasePath(path) must convert backslashes to forward slashes.
     */
    @Test
    public void testCreateBasePathNormalizesBackslashes() {
        String tmpDir = System.getProperty("java.io.tmpdir");
        String withBackslashes = tmpDir.replace('/', '\\');
        String result = FileLeaderElectionConfiguration.createBasePath(withBackslashes);
        assertFalse(result.contains("\\"),
                "createBasePath must replace backslashes with forward slashes: " + result);
    }
}
