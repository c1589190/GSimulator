package com.gsim.worldinfo.loader;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class StateManagerTest {

    @TempDir
    Path tmpDir;

    @Test
    void saveAndLoadActiveState() {
        ActiveStateManager.ActiveState state =
                new ActiveStateManager.ActiveState(Map.of("Orchestrator", "Orchestrator_2026-06-26T100000.json"));

        ActiveStateManager.save(tmpDir, "test-world", state);

        ActiveStateManager.ActiveState loaded = ActiveStateManager.load(tmpDir, "test-world");
        assertNotNull(loaded);
        assertEquals("Orchestrator_2026-06-26T100000.json", loaded.sessions().get("Orchestrator"));
    }

    @Test
    void loadMissingActiveStateReturnsNull() {
        ActiveStateManager.ActiveState loaded = ActiveStateManager.load(tmpDir, "no-world");
        assertNull(loaded);
    }

    @Test
    void createWorldMakesAllFiles() {
        WorldIndexManager.createWorld(tmpDir, "my-world", "测试世界");

        // world.json exists
        assertNotNull(WorldIndexManager.loadWorldMeta(tmpDir, "my-world"));

        // n0000.json exists
        assertNotNull(NodeLoader.load(NodeLoader.nodeFile(tmpDir, "my-world", "n0000")));

        // world is listed via directory scan
        var entries = WorldIndexManager.listWorlds(tmpDir);
        assertEquals(1, entries.size());
        assertEquals("my-world", entries.get(0).id());

        // active.json exists (sessions only)
        var active = ActiveStateManager.load(tmpDir, "my-world");
        assertNotNull(active);
    }

    @Test
    void listWorldsEmptyByDefault() {
        assertTrue(WorldIndexManager.listWorlds(tmpDir).isEmpty());
    }
}
