package com.gsim.context;

import com.gsim.app.AppConfig;
import com.gsim.context.summary.BranchPathSummaryRenderer;
import com.gsim.context.summary.NodeSummaryStore;
import com.gsim.data.DataManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * BranchPathSummaryRenderer 测试。
 */
@DisplayName("BranchPathSummaryRenderer")
class BranchPathSummaryRendererTest {

    @TempDir
    Path tempDir;
    private DataManager dataManager;
    private BranchPathSummaryRenderer renderer;

    @BeforeEach
    void setUp() throws Exception {
        AppConfig config = AppConfig.forTesting();
        Path dataRoot = tempDir.resolve("data");
        java.nio.file.Files.createDirectories(dataRoot);

        dataManager = com.gsim.TestWorldFactory.createWithDefaultRoot(dataRoot);
        // initDefault called automatically by constructor

        Path worldDir = dataRoot.resolve("worlds").resolve("default");
        var summaryStore = new NodeSummaryStore(worldDir);
        renderer = new BranchPathSummaryRenderer(dataManager, summaryStore);
    }

    @Test
    @DisplayName("应渲染根节点概要链")
    void shouldRenderPathForRootNode() {
        String path = renderer.renderPath("branch.b0000-start",
                new BranchPathSummaryRenderer.BranchPathRenderOptions());
        assertNotNull(path);
        assertFalse(path.isBlank());
        assertTrue(path.contains("Branch Evolution Summary"));
    }

    @Test
    @DisplayName("无 NodeSummary 时应 fallback")
    void shouldFallbackWhenNoSummary() {
        String path = renderer.renderActivePath();
        assertNotNull(path);
        assertTrue(path.contains("Branch Evolution Summary") || path.contains("暂无摘要"));
    }
}
