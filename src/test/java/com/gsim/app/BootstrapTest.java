package com.gsim.app;

import com.gsim.cache.CachesManager;
import com.gsim.cache.FileSystemCachesManager;
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
}
