package com.gsim.app;

import com.gsim.cache.CachesManager;
import com.gsim.cache.FileSystemCachesManager;
import com.gsim.worldinfo.loader.WorldIndexManager;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class BootstrapTest {

    @TempDir
    Path tmpDir;

    @Test
    void bootCreatesDefaultWorld() throws Exception {
        Path worldsDir = tmpDir.resolve("worlds");
        Path promptsDir = tmpDir.resolve("prompts");
        Files.createDirectories(promptsDir);

        CachesManager cachesManager = new FileSystemCachesManager(worldsDir);
        Bootstrap b = new Bootstrap(worldsDir, promptsDir, cachesManager);
        Bootstrap.BootstrapResult result = b.boot();

        assertEquals("default", result.worldId());
        assertEquals("n0000", result.activeNodeId());
        assertNotNull(result.worldInfo());
        assertNotNull(result.activeCache());
        // System prompt is injected dynamically at runtime (not cached)
        assertEquals(0, result.activeCache().messageCount());
    }

    @Test
    void bootWithExplicitWorldId() throws Exception {
        Path worldsDir = tmpDir.resolve("worlds");
        Path promptsDir = tmpDir.resolve("prompts");
        Files.createDirectories(promptsDir);

        // Create two worlds to verify we can pick the second one
        WorldIndexManager.createWorld(worldsDir, "alpha", "Alpha World");
        WorldIndexManager.createWorld(worldsDir, "zeta", "Zeta World");

        CachesManager cachesManager = new FileSystemCachesManager(worldsDir);
        Bootstrap b = new Bootstrap(worldsDir, promptsDir, cachesManager);
        // Without explicit worldId, first world (alpha) would be picked
        // With explicit worldId, we should get zeta
        Bootstrap.BootstrapResult result = b.boot(null, "zeta");

        assertEquals("zeta", result.worldId());
        assertEquals("n0000", result.activeNodeId());
        assertNotNull(result.worldInfo());
        assertNotNull(result.activeCache());
        assertEquals(0, result.activeCache().messageCount());
    }

    @Test
    void bootNonexistentWorldThrows() throws Exception {
        Path worldsDir = tmpDir.resolve("worlds");
        Path promptsDir = tmpDir.resolve("prompts");
        Files.createDirectories(promptsDir);

        CachesManager cachesManager = new FileSystemCachesManager(worldsDir);
        Bootstrap b = new Bootstrap(worldsDir, promptsDir, cachesManager);

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> b.boot(null, "nonexistent"));
        assertTrue(ex.getMessage().contains("nonexistent"));
    }
}
