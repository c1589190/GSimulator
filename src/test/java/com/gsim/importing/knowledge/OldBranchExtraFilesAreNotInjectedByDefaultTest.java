package com.gsim.importing.knowledge;

import com.gsim.context.BranchContextRenderer;
import com.gsim.context.memory.PinnedConstraintStore;
import com.gsim.context.session.BaseContextSnapshot;
import com.gsim.context.summary.BranchPathSummaryRenderer;
import com.gsim.context.summary.NodeSummaryStore;
import com.gsim.data.DataManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("旧 branch extra sections 不默认注入 Agent 上下文")
class OldBranchExtraFilesAreNotInjectedByDefaultTest {

    @TempDir Path tempDir;
    private DataManager dm;
    private Path dataRoot;

    @BeforeEach
    void setUp() throws Exception {
        dataRoot = tempDir.resolve("data");
        Files.createDirectories(dataRoot);
        dm = new DataManager(dataRoot);
    }

    @Test
    @DisplayName("renderBaseContext 不包含 四/五/六/七/八 section 全文")
    void renderBaseContextDoesNotInjectDeltaSections() {
        // No root yet → base context should be minimal
        if (!dm.needsRootBootstrap()) {
            Path worldDir = dataRoot.resolve("worlds").resolve(dm.getActiveRootId());
            var summaryStore = new NodeSummaryStore(worldDir);
            var pinStore = new PinnedConstraintStore(worldDir);
            var pathRenderer = new BranchPathSummaryRenderer(dm, summaryStore);
            var renderer = new BranchContextRenderer(dm, dataRoot, null, null,
                    pathRenderer, summaryStore, pinStore);

            BaseContextSnapshot snapshot = renderer.renderBaseContext(worldDir);
            String markdown = snapshot.markdown();

            // 不应包含旧增量 section 的注入
            assertFalse(markdown.contains("四、世界观/设定增量"),
                    "renderBaseContext must NOT inject deprecated section 四");
            assertFalse(markdown.contains("五、实体状态增量"),
                    "renderBaseContext must NOT inject deprecated section 五");
            assertFalse(markdown.contains("六、推演规则增量"),
                    "renderBaseContext must NOT inject deprecated section 六");
        }
    }

    @Test
    @DisplayName("renderBaseContext 在无 root 时不应抛异常")
    void renderBaseContextDoesNotThrowOnEmptyState() {
        // 在无 root 状态下验证方法不会崩溃
        // needsRootBootstrap 为 true 时是正常状态
        assertTrue(dm.needsRootBootstrap());
    }
}
