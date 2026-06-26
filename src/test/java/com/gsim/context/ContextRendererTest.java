package com.gsim.context;

import com.gsim.worldinfo.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ContextRendererTest {

    @TempDir
    Path tmpDir;

    @Test
    void renderSystemPromptInjectsWorldInfo() throws Exception {
        // Write a minimal template
        Files.createDirectories(tmpDir);
        Files.writeString(tmpDir.resolve("OrchestratorAgent_system.md"),
            "# Engine\nWorld: ${worldId}\nTurn: ${activeTurn}\nCheckpoints: ${checkpointIds?join(\", \")}");

        NodeSnapshot n0 = new NodeSnapshot("n0000", null, 0, "origin", "initial", "t0",
            Map.of("worldview", new Checkpoint("世界观", "worldview", List.of(
                Element.simple("k", "text", "v")))));

        WorldInformation wi = new WorldInformation("test", List.of(n0));

        ContextRenderer renderer = new ContextRenderer(tmpDir);
        String result = renderer.renderSystemPrompt("OrchestratorAgent", wi);

        assertTrue(result.contains("World: test"));
        assertTrue(result.contains("Turn: 0"));
        assertTrue(result.contains("worldview"));
    }

    @Test
    void missingTemplateFallsBackToRawFile() throws Exception {
        Files.createDirectories(tmpDir);
        Files.writeString(tmpDir.resolve("TestAgent_system.md"), "Raw content without variables");

        NodeSnapshot n0 = new NodeSnapshot("n0000", null, 0, "origin", "initial", "t0", Map.of());
        WorldInformation wi = new WorldInformation("test", List.of(n0));

        ContextRenderer renderer = new ContextRenderer(tmpDir);
        String result = renderer.renderSystemPrompt("TestAgent", wi);

        assertEquals("Raw content without variables", result.trim());
    }
}
