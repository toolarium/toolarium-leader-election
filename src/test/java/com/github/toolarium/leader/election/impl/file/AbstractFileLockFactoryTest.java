/*
 * AbstractFileLockFactoryTest.java
 *
 * Copyright by toolarium, all rights reserved.
 */
package com.github.toolarium.leader.election.impl.file;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import com.github.toolarium.leader.election.impl.file.exception.FileLockException;
import java.io.File;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.TestInfo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


/**
 * Base class for FileLockFactory tests
 * 
 * @author patrick
 */
public abstract class AbstractFileLockFactoryTest {
    private static final Logger LOG = LoggerFactory.getLogger(AbstractFileLockFactoryTest.class);
    
    /**
     * Setup
     * 
     * @param testInfo the test information
     */
    @BeforeEach
    public void setUp(TestInfo testInfo) {
        LOG.debug("\n---- " + testInfo.getTestClass().get().getCanonicalName() + "#" + testInfo.getDisplayName() + " ----");
        FileLockFactory.getInstance().setScheduleInterval(1);
        LOG.debug("RESTART");

        try {
            Thread.sleep(1000);
        } catch (InterruptedException e) {
            // NOP
        }
    }

    
    /**
     * Create the file lock
     * 
     * @param file the file
     * @return the file lock
     */
    protected FileLock createFileLock(File file) {
        FileLock fileLock = null;
        
        try {
            fileLock = FileLockFactory.getInstance().tryLock(file);
            assertEquals(file, fileLock.getFile());
            assertValidFileLock(fileLock);
            assertNull(fileLock.getLockPeriod());
            assertNull(fileLock.getRenewDeadline());
        } catch (FileLockException e) {
            LOG.debug("Error :" + e.getMessage(), e);
            fail();
        }
        
        return fileLock;
    }

    
    /**
     * Close file lock
     * 
     * @param file the file
     * @param fileLock the file lock
     */
    protected void closeFileLock(File file, FileLock fileLock) {
        try {
            fileLock.close();
            assertEquals(file, fileLock.getFile());
            assertInValidFileLock(fileLock);
            assertNull(fileLock.getLockPeriod());
            assertNull(fileLock.getRenewDeadline());

        } catch (Exception e) {
            fail();
        }
    }

    
    /**
     * Assert valid file lock
     * 
     * @param fileLock the file lock
     */
    protected void assertValidFileLock(FileLock fileLock) {
        assertValidFileLock(fileLock, 1);
    }

    
    /**
     * Assert valid file lock
     * 
     * @param fileLock the file lock
     * @param id the id
     */
    protected void assertValidFileLock(FileLock fileLock, long id) {
        assertEquals(System.getProperty("user.name"), fileLock.getUser());
        assertEquals(id, fileLock.getThreadId());
        assertTrue(fileLock.hasLock());
    }

    
    /**
     * Assert invalid file lock
     * 
     * @param fileLock the file lock
     */
    protected void assertInValidFileLock(FileLock fileLock) {
        assertNull(fileLock.getUser()); 
        assertNull(fileLock.getThreadId()); 
        assertFalse(fileLock.hasLock());
    }
}
