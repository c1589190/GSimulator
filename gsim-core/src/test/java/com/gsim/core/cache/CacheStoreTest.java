package com.gsim.core.cache;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CacheStoreTest {

    @TempDir
    Path tmpDir;

    @BeforeEach
    void configureCachesRoot() {
        CacheStore.setCachesRoot(tmpDir);
    }

    @Test
    void saveAndLoadRoundtrip() {
        CacheSession session = CacheStore.createNew("Orchestrator");

        session.addMessage(Map.of("role", "system", "content", "You are a simulation engine."));
        session.addMessage(Map.of("role", "user", "content", "Hello"));
        CacheStore.save(session);

        CacheSession loaded = CacheStore.load(session.sessionId());
        assertNotNull(loaded);
        assertEquals("Orchestrator", loaded.agentName());
        assertEquals(2, loaded.messageCount());
        assertEquals("system", loaded.messages().get(0).get("role"));
        assertEquals("Hello", loaded.messages().get(1).get("content"));
    }

    @Test
    void loadMissingReturnsNull() {
        assertNull(CacheStore.load("nonexistent.json"));
    }

    @Test
    void compressionChain() {
        CacheSession old = CacheStore.createNew("Orchestrator");
        old.compressionNote("Summary of old session.");

        CacheSession fresh = CacheStore.createNew("Orchestrator");
        fresh.previousSessionId(old.sessionId());
        fresh.compressionNote("Continuing from previous...");

        CacheStore.save(old);
        CacheStore.save(fresh);

        CacheSession loaded = CacheStore.load(fresh.sessionId());
        assertEquals(old.sessionId(), loaded.previousSessionId());
        assertEquals("Continuing from previous...", loaded.compressionNote());
    }

    @Test
    void createsCachesDirectory() {
        CacheStore.createNew("Sim");
        assertTrue(java.nio.file.Files.exists(CacheStore.cachesDir()));
    }

    @Test
    void cachesDirThrowsWhenNotConfigured() {
        CacheStore.setCachesRoot(null);
        assertThrows(IllegalStateException.class, CacheStore::cachesDir);
    }
}
