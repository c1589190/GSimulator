package com.gsim.integration;

import com.gsim.app.Bootstrap;
import com.gsim.cache.CacheSession;
import com.gsim.cache.CacheStore;
import com.gsim.context.ContextRenderer;
import com.gsim.worldinfo.Element;
import com.gsim.worldinfo.WorldInformation;
import com.gsim.worldinfo.loader.NodeLoader;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Full end-to-end integration test: Bootstrap creates world -> write elements
 * -> query -> cache -> render. Validates the complete lifecycle in isolation
 * using @TempDir.
 */
class EndToEndTest {

    @TempDir
    Path tmpDir;

    @Test
    void fullLifecycle() throws Exception {
        Path worldsDir = tmpDir.resolve("worlds");
        Path promptsDir = tmpDir.resolve("prompts");
        Files.createDirectories(promptsDir);
        Files.writeString(promptsDir.resolve("OrchestratorAgent_system.md"),
            "System: ${worldId}, turn ${activeTurn}");

        // --- Bootstrap creates default world ---
        Bootstrap b = new Bootstrap(worldsDir, promptsDir);
        Bootstrap.BootstrapResult result = b.boot();

        assertEquals("default", result.worldId());
        assertEquals("n0000", result.activeNodeId());
        assertNotNull(result.worldInfo());

        // --- Write some elements ---
        WorldInformation wi = result.worldInfo();
        wi.appendElement("n0000", "worldview",
            new Element("气候", "text", "中原大旱",
                List.of("气候"), List.of()));

        // persist
        NodeLoader.save(NodeLoader.nodeFile(worldsDir, "default", "n0000"), wi.nodeById("n0000"));

        // --- Query ---
        assertEquals(1, wi.checkpointHistory("worldview").size());
        assertFalse(wi.keywordIndex().search("中原", 10, 0).items().isEmpty());

        // --- Cache ---
        CacheSession cache = result.activeCache();
        cache.addMessage(Map.of("role", "user", "content", "测试消息"));
        CacheStore.save(worldsDir, "default", cache);

        CacheSession loaded = CacheStore.load(worldsDir, "default", cache.sessionId());
        assertNotNull(loaded);
        assertEquals(2, loaded.messageCount()); // system + user

        // --- Context rendering ---
        ContextRenderer renderer = result.contextRenderer();
        String prompt = renderer.renderSystemPrompt("OrchestratorAgent", wi);
        assertTrue(prompt.contains("default"));
        assertTrue(prompt.contains("turn 0"));
    }
}
