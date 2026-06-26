package com.gsim.cache;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class CacheStoreTest {

    @TempDir
    Path tmpDir;

    @Test
    void saveAndLoadRoundtrip() {
        CacheSession session = CacheStore.createNew(tmpDir, "test-world", "Orchestrator", "n0000");

        session.addMessage(Map.of("role", "system", "content", "You are a simulation engine."));
        session.addMessage(Map.of("role", "user", "content", "Hello"));
        CacheStore.save(tmpDir, "test-world", session);

        CacheSession loaded = CacheStore.load(tmpDir, "test-world", session.sessionId());
        assertNotNull(loaded);
        assertEquals("Orchestrator", loaded.agentName());
        assertEquals(2, loaded.messageCount());
        assertEquals("system", loaded.messages().get(0).get("role"));
        assertEquals("Hello", loaded.messages().get(1).get("content"));
    }

    @Test
    void loadMissingReturnsNull() {
        assertNull(CacheStore.load(tmpDir, "no-world", "nonexistent.json"));
    }

    @Test
    void compressionChain() {
        CacheSession old = CacheStore.createNew(tmpDir, "w", "Orchestrator", "n0002");
        old.compressionNote("Summary of old session.");

        CacheSession fresh = CacheStore.createNew(tmpDir, "w", "Orchestrator", "n0003");
        fresh.previousSessionId(old.sessionId());
        fresh.compressionNote("Continuing from previous...");

        CacheStore.save(tmpDir, "w", old);
        CacheStore.save(tmpDir, "w", fresh);

        CacheSession loaded = CacheStore.load(tmpDir, "w", fresh.sessionId());
        assertEquals(old.sessionId(), loaded.previousSessionId());
        assertEquals("Continuing from previous...", loaded.compressionNote());
    }

    @Test
    void createsCachesDirectory() {
        CacheStore.createNew(tmpDir, "w", "Sim", "n0000");
        assertTrue(java.nio.file.Files.exists(CacheStore.cachesDir(tmpDir, "w")));
    }
}
