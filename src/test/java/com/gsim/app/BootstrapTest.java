package com.gsim.app;

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
        Files.writeString(promptsDir.resolve("OrchestratorAgent_system.md"),
            "System: ${worldId}, turn ${activeTurn}");

        Bootstrap b = new Bootstrap(worldsDir, promptsDir);
        Bootstrap.BootstrapResult result = b.boot();

        assertEquals("default", result.worldId());
        assertEquals("n0000", result.activeNodeId());
        assertNotNull(result.worldInfo());
        assertNotNull(result.activeCache());
        assertEquals(1, result.activeCache().messageCount());
    }
}
