/*
 * FileLockFactoryTest.java
 *
 * Copyright by toolarium, all rights reserved.
 */
package com.github.toolarium.leader.election.impl.file;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import com.github.toolarium.leader.election.impl.file.exception.FileLockException;
import java.io.File;
import java.nio.file.Paths;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


/**
 * Test the FileLockFactory
 *  
 * @author patrick
 */
public class FileLockFactoryTest extends AbstractFileLockFactoryTest {
    static final Logger LOG = LoggerFactory.getLogger(FileLockFactoryTest.class);

    
    /**
     * Test timeout lock
     *
     * @throws Exception In case of a file lock exception
     */
    @Test
    public void testTimeoutLock() throws Exception {
        File file = Paths.get("build", "testTimeoutLock.lock").toFile();
        
        FileLock fileLock = FileLockFactory.getInstance().tryLock(file, Duration.ofSeconds(2));
        assertValidFileLock(fileLock);
        
        Thread.sleep(5 * 1000);
        assertInValidFileLock(fileLock);
        
        fileLock.close();
    }


    /**
     * Test timeout lock
     *
     * @throws Exception In case of a file lock exception
     */
    @Test
    public void testRenewLock() throws Exception {
        File file = Paths.get("build", "testRenewLock.lock").toFile();
        
        FileLock fileLock = FileLockFactory.getInstance().tryLock(file, Duration.ofSeconds(2), Duration.ofSeconds(2));
        assertValidFileLock(fileLock);
        
        Thread.sleep(5 * 1000);
        assertValidFileLock(fileLock);
        
        fileLock.close();
    }


    /**
     * Test timeout lock
     *
     * @throws FileLockException In case of a file lock exception
     * @throws InterruptedException Interrupt error
     */
    @Test
    public void testLostLockAndWinBack() throws FileLockException, InterruptedException {
        File file = Paths.get("build", "testLostLockAndWinBack.lock").toFile();
        
        FileLock fileLock = FileLockFactory.getInstance().tryLock(file, Duration.ofSeconds(2), Duration.ofSeconds(2));
        assertValidFileLock(fileLock);

        Thread thread = new Thread(FileLockFactoryTest.class.getName() + ": Update thread") { 
            /**
             * @see java.lang.Thread#run()
             */
            @Override
            public void run() {
                FileLock fileLock = FileLockFactory.getInstance().tryLock(file, Duration.ofSeconds(1));
                
                try {
                    fileLock.lock();
                    fail();
                } catch (FileLockException e) {
                    // NOP
                }

                try {
                    Thread.sleep(2000);
                } catch (InterruptedException e) {
                    // NOP
                }
                
                boolean runThread = true;
                while (runThread && !Thread.currentThread().isInterrupted()) {
                    if (!fileLock.hasLock()) {
                        try {
                            fileLock.lock();
                        } catch (FileLockException e) {
                            // NOP
                        }
                    }
                    
                    if (fileLock.hasLock()) {
                        LOG.debug("Got it..");
                        assertValidFileLock(fileLock, Thread.currentThread().threadId());
                        runThread = false;
                    } else {
                       // LOG.debug("Release...");
                    }
                }

                assertTrue(fileLock.hasLock());
                LOG.debug("Valid: " + fileLock);
                
                try {
                    Thread.sleep(4000);
                } catch (InterruptedException e) {
                    LOG.debug("Interrupted: " + e.getMessage(), e);
                    // NOP
                }

                assertFalse(fileLock.hasLock());
                
                try {
                    LOG.debug("Release losed lock: " + fileLock);
                    fileLock.close();
                } catch (Exception e) {
                    LOG.debug("Error occured:" + e.getMessage(), e);
                }
                
                LOG.debug("End thread.");
            }
        };
        
        thread.setDaemon(true);
        thread.start();

        Thread.sleep(6000);
            
        while (!fileLock.hasLock()) {
            Thread.sleep(500);
            
        }
        
        LOG.debug("TEST.");
        assertValidFileLock(fileLock);
        
        try {
            LOG.debug("Release lock: " + fileLock);
            fileLock.close();
        } catch (Exception e) {
            LOG.debug("Error occured:" + e.getMessage(), e);
        }
    }
}
