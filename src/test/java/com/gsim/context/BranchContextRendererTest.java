package com.gsim.context;

import com.gsim.data.DataManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("BranchContextRenderer")
class BranchContextRendererTest {
    @TempDir Path tempDir;
    private DataManager dm;
    private BranchContextRenderer renderer;

    @BeforeEach void setUp() {
        dm = new DataManager(tempDir);
        renderer = new BranchContextRenderer(dm, tempDir);
    }

    @Test @DisplayName("System.md auto-created")
    void testSystemPromptCreated() {
        assertTrue(renderer.ensureSystemPrompt());
        assertFalse(renderer.getSystemPrompt().isBlank());
    }

    @Test @DisplayName("render produces messages with system + data + input")
    void testRender() {
        RenderedContext ctx = renderer.render();
        assertTrue(ctx.systemPromptExists());
        assertFalse(ctx.messages().isEmpty());
        assertTrue(ctx.messages().stream().anyMatch(m -> "system_prompt".equals(m.type())));
        assertTrue(ctx.messages().stream().anyMatch(m -> "effective_data".equals(m.type())));
        assertTrue(ctx.messages().stream().anyMatch(m -> "current_input".equals(m.type())));
    }

    @Test @DisplayName("branch messages extracted from LLM context record")
    void testBranchMessages() throws Exception {
        dm.appendInput("测试输入");
        dm.createBranch("branch.b0001-test", "测试", "T1");
        RenderedContext ctx = renderer.render();
        assertEquals("branch.b0001-test", ctx.activeBranch());
        assertTrue(ctx.chainLength() >= 2);
    }

    @Test @DisplayName("brother branch not rendered after switch")
    void testBrotherNotRendered() throws Exception {
        dm.createBranch("branch.b0001-a", "A", "T1");
        dm.switchBranch("branch.b0000-start");
        dm.createBranch("branch.b0001-b", "B", "T2");
        RenderedContext ctx = renderer.render();
        // 不应该包含 branch.b0001-a (兄弟分支)
        String md = renderer.renderAsMarkdown();
        assertTrue(md.contains("branch.b0001-b"));
        assertFalse(md.contains("branch.b0001-a"));
    }

    @Test @DisplayName("renderAsMarkdown produces valid output")
    void testRenderAsMarkdown() {
        String md = renderer.renderAsMarkdown();
        assertTrue(md.contains("Rendered Context"));
        assertTrue(md.contains("branch.b0000-start"));
    }
}
