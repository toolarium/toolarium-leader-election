/*
 * FileLockNameTest.java
 *
 * Copyright by toolarium, all rights reserved.
 */
package com.github.toolarium.leader.election.impl.file;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.github.toolarium.leader.election.ILeaderElector;
import com.github.toolarium.leader.election.LeaderElectionFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import org.junit.jupiter.api.Test;


/**
 * Tests that the namespace and name of a FILE election cannot place the lock file outside the base path.
 *
 * @author patrick
 */
public class FileLockNameTest {

    /**
     * Regular names are kept; path separators and other special characters are replaced.
     */
    @Test
    public void testCreateLockFileName() {
        assertEquals("my-namespace.my_app.lock", FileLeaderElectorImpl.createLockFileName("my-namespace.my_app"));
        assertTrue(FileLeaderElectorImpl.createLockFileName(" my-namespace.my_app ").matches("my-namespace\\.my_app-[0-9a-f]{8}\\.lock"));
        assertTrue(FileLeaderElectorImpl.createLockFileName("../../etc/passwd").matches("\\.\\._\\.\\._etc_passwd-[0-9a-f]{8}\\.lock"));
        assertTrue(FileLeaderElectorImpl.createLockFileName("..\\..\\evil").matches("\\.\\._\\.\\._evil-[0-9a-f]{8}\\.lock"));
        assertTrue(FileLeaderElectorImpl.createLockFileName("C:/temp/x").matches("C__temp_x-[0-9a-f]{8}\\.lock"));
        assertEquals(".lock", FileLeaderElectorImpl.createLockFileName(null));

        // names that only differ in replaced characters get different lock files
        assertFalse(FileLeaderElectorImpl.createLockFileName("a/b").equals(FileLeaderElectorImpl.createLockFileName("a_b")));
        assertFalse(FileLeaderElectorImpl.createLockFileName("a/b").equals(FileLeaderElectorImpl.createLockFileName("a:b")));
    }


    /**
     * A name with path traversal creates the lock file directly in the base path.
     *
     * @throws Exception in case of an error
     */
    @Test
    public void testTraversalNameStaysInBasePath() throws Exception {
        Path basePath = Files.createTempDirectory(Paths.get("build").toAbsolutePath(), "file-lock-name");
        Path escaped = basePath.getParent().resolve("escaped.lock");
        Files.deleteIfExists(escaped);

        ILeaderElector elector = LeaderElectionFactory.builder().name("../escaped").timeout(Duration.ofSeconds(3)).file().basePath(basePath.toString()).build();
        try {
            assertTrue(elector.elect());
            assertTrue(Files.exists(basePath.resolve(FileLeaderElectorImpl.createLockFileName("../escaped"))), "The lock file must be created in the base path");
            assertFalse(Files.exists(escaped), "The lock file must not be created outside the base path");
        } finally {
            elector.close();
        }
    }
}
