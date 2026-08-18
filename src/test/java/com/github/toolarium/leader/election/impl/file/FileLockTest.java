/*
 * FileLockTest.java
 *
 * Copyright by toolarium, all rights reserved.
 */
package com.github.toolarium.leader.election.impl.file;

import static org.junit.jupiter.api.Assertions.assertTrue;

import com.github.toolarium.leader.election.impl.file.exception.FileLockException;
import java.io.File;
import java.nio.file.Paths;
import org.junit.jupiter.api.Test;


/**
 * Test the file lock api
 *
 * @author patrick
 */
public class FileLockTest extends AbstractFileLockFactoryTest {

    /**
     * Test lock and close
     */
    @Test
    public void testLockAndClose() {
        File file = Paths.get("build", "testLockAndClose.lock").toFile();

        // create lock
        FileLock fileLock = createFileLock(file);

        // close twice
        closeFileLock(file, fileLock);
        closeFileLock(file, fileLock);

        fileLock = createFileLock(file);

        closeFileLock(file, fileLock);
    }


    /**
     * Test lock and close
     * @throws InterruptedException Interrupt exception
     * @throws FileLockException File lock could not be aquired
     */
    @Test
    public void testRenew() throws InterruptedException, FileLockException {
        File file = Paths.get("build", "testRenew.lock").toFile();

        // create lock
        FileLock fileLock = createFileLock(file);
        long timestamp = fileLock.getLockTime().toEpochMilli();

        Thread.sleep(1000);
        fileLock.renew();

        assertTrue(timestamp < fileLock.getLockTime().toEpochMilli());

        closeFileLock(file, fileLock);
    }
}
