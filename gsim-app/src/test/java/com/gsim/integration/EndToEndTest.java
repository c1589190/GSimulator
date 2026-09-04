package com.gsim.integration;

import static org.junit.jupiter.api.Assertions.*;

import com.gsim.app.Bootstrap;
import com.gsim.agentsmanager.cache.CacheSession;
import com.gsim.agentsmanager.cache.CacheStore;
import com.gsim.agentsmanager.cache.CachesManager;
import com.gsim.agentsmanager.cache.FileSystemCachesManager;
import com.gsim.core.worldinfo.Element;
import com.gsim.core.worldinfo.WorldInformation;
import com.gsim.core.worldinfo.loader.NodeLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Full end-to-end integration test: Bootstrap creates world -> write elements
 * -> query -> cache -> render. Validates the complete lifecycle in isolation
 * using @TempDir.
 */
class EndToEndTest {

    @TempDir
    Path tmpDir;

    @BeforeEach
    void setUp() {
        CacheStore.setCachesRoot(tmpDir);
    }

    @Test
    void fullLifecycle() throws Exception {
        Path worldsDir = tmpDir.resolve("worlds");
        Path promptsDir = tmpDir.resolve("prompts");
        Files.createDirectories(promptsDir);
        Files.writeString(promptsDir.resolve("OrchestratorAgent_system.md"), "System: ${worldId}, turn ${activeTurn}");

        // --- Bootstrap creates default world ---
        CachesManager cachesManager = new FileSystemCachesManager();
        Bootstrap b = new Bootstrap(worldsDir, promptsDir, cachesManager);
        Bootstrap.BootstrapResult result = b.boot();

        assertEquals("default", result.worldId());
        assertEquals("n0000", result.activeNodeId());
        assertNotNull(result.worldInfo());

        // --- Write some elements ---
        WorldInformation wi = result.worldInfo();
        wi.appendElement("n0000", "worldview", new Element("气候", "text", "中原大旱", List.of("气候"), List.of(), null, null));

        // persist
        NodeLoader.save(NodeLoader.nodeFile(worldsDir, "default", "n0000"), wi.nodeById("n0000"));

        // --- Query ---
        assertEquals(1, wi.checkpointHistory("worldview").size());
        assertFalse(wi.keywordIndex().search("中原", 10, 0).items().isEmpty());

        // --- Cache ---
        CacheSession cache = result.activeCache();
        cache.addMessage(Map.of("role", "user", "content", "测试消息"));
        CacheStore.save(cache);

        CacheSession loaded = CacheStore.load(cache.sessionId());
        assertNotNull(loaded);
        assertEquals(1, loaded.messageCount());
    }
}
