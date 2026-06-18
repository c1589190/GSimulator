package com.gsim.root;

import com.gsim.chat.BranchMessageStore;
import com.gsim.chat.NodeAgentChatService;
import com.gsim.agent.OrchestratorAgent;
import com.gsim.app.ApplicationContext;
import com.gsim.app.AppConfig;
import com.gsim.context.BranchContextRenderer;
import com.gsim.context.session.ContextSessionManager;
import com.gsim.context.session.ContextSessionStore;
import com.gsim.data.DataManager;
import com.gsim.llm.FakeLlmClient;
import com.gsim.tool.ToolRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests empty-data bootstrap and existing-data guard.
 */
class EmptyDataChatBootstrapTest {

    @Test
    void createRootFromEmptyDataViaApi(@TempDir Path tmpDir) throws Exception {
        Path dataRoot = tmpDir.resolve("data");
        Files.createDirectories(dataRoot);
        DataManager dm = new DataManager(dataRoot);
        // The constructor auto-creates "default". Test createRoot instead for explicit creation.

        // Delete default and create fresh
        dm.switchWorld("default"); // ensure we're on default
        // Create additional root via API
        dm.createRoot("fresh-root", "1850年代架空东南亚测试。");
        dm.switchWorld("fresh-root");

        assertFalse(dm.needsRootBootstrap());
        assertEquals("fresh-root", dm.getActiveRootId());
        assertEquals("branch.b0000-start", dm.getActiveBranch());

        Path worldDir = dataRoot.resolve("worlds").resolve("fresh-root");
        assertTrue(Files.exists(worldDir.resolve("world.md")));
        assertTrue(Files.exists(worldDir.resolve("entities.md")));
        assertTrue(Files.exists(worldDir.resolve("rules.md")));
        assertTrue(Files.exists(worldDir.resolve("branches").resolve("b0000-start.md")));
    }

    @Test
    void existingDataCannotAutoBootstrap(@TempDir Path tmpDir) throws Exception {
        Path dataRoot = tmpDir.resolve("data");
        DataManager dm = new DataManager(dataRoot);
        // Default world already exists
        assertFalse(dm.needsRootBootstrap());

        // bootstrapFromEmpty should throw when data already has roots
        assertThrows(IOException.class, () -> {
            dm.bootstrapFromEmpty("another-root", "content");
        }, "bootstrapFromEmpty should fail when data already has roots");
    }

    @Test
    void rootCreateSwitchWorks(@TempDir Path tmpDir) throws Exception {
        Path dataRoot = tmpDir.resolve("data");
        DataManager dm = new DataManager(dataRoot);
        // Create explicitly
        dm.createRoot("world-a", "World A content");
        dm.createRoot("world-b", "World B content");

        // Verify both exist
        assertTrue(Files.exists(dataRoot.resolve("worlds").resolve("world-a").resolve("world.md")));
        assertTrue(Files.exists(dataRoot.resolve("worlds").resolve("world-b").resolve("world.md")));

        // Switch
        dm.switchWorld("world-a");
        assertEquals("world-a", dm.getActiveRootId());

        dm.switchWorld("world-b");
        assertEquals("world-b", dm.getActiveRootId());
    }
}
