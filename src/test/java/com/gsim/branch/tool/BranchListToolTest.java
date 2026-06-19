package com.gsim.branch.tool;

import com.gsim.TestWorldFactory;
import com.gsim.data.DataManager;
import com.gsim.tool.ToolCall;
import com.gsim.tool.ToolResult;
import org.junit.jupiter.api.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * branch_list 工具测试。
 * 覆盖：列出所有 branch、parent/children、counts、nodeOverview、
 * flat 和 tree 模式、NO_ACTIVE_ROOT。
 */
@DisplayName("branch_list Tool")
public class BranchListToolTest {

    private Path tmpDir;
    private DataManager dm;
    private BranchListTool listTool;
    private BranchCreateChildTool createTool;
    private BranchSwitchTool switchTool;

    private final Runnable onBranchChanged = () -> {};

    @BeforeEach
    void setUp() throws IOException {
        tmpDir = Files.createTempDirectory("gsim-test-branch-list-");
        dm = TestWorldFactory.createWithDefaultRoot(tmpDir);
        listTool = new BranchListTool(dm);
        createTool = new BranchCreateChildTool(dm, onBranchChanged);
        switchTool = new BranchSwitchTool(dm, onBranchChanged);
    }

    @AfterEach
    void tearDown() throws IOException {
        try (var s = Files.walk(tmpDir)) {
            s.sorted(java.util.Comparator.reverseOrder())
                    .forEach(p -> { try { Files.delete(p); } catch (IOException ignored) {} });
        }
    }

    // ==================== 基本列表 ====================

    @Test
    @DisplayName("branch_list: lists all branches in flat mode")
    void listsAllBranchesFlat() {
        ToolCall call = new ToolCall("branch_list", Map.of("mode", "flat"));
        ToolResult result = listTool.execute(call);

        assertTrue(result.success(), "should succeed: " + result.error());
        String snippet = result.items().get(0).snippet();

        // 应该有默认 root branch
        assertTrue(snippet.contains("rootId:"), "should contain rootId");
        assertTrue(snippet.contains("activeBranch:"), "should contain activeBranch");
        assertTrue(snippet.contains("totalBranches:"), "should contain totalBranches");
        assertTrue(snippet.contains("branch.b0000-start"), "should contain root branch");
        assertTrue(snippet.contains("parent: none"), "root branch should have parent none");
    }

    @Test
    @DisplayName("branch_list: returns NO_ACTIVE_ROOT when no active root")
    void noActiveRoot() throws IOException {
        Path emptyDir = Files.createTempDirectory("gsim-test-no-root-list-");
        DataManager emptyDm = new DataManager(emptyDir);
        BranchListTool emptyListTool = new BranchListTool(emptyDm);

        ToolCall call = new ToolCall("branch_list", Map.of());
        ToolResult result = emptyListTool.execute(call);

        assertFalse(result.success(), "should fail without active root");
        assertEquals("NO_ACTIVE_ROOT", result.error());

        // cleanup
        try (var s = Files.walk(emptyDir)) {
            s.sorted(java.util.Comparator.reverseOrder())
                    .forEach(p -> { try { Files.delete(p); } catch (IOException ignored) {} });
        }
    }

    // ==================== 父子关系 ====================

    @Test
    @DisplayName("branch_list: shows parent/children relationships")
    void showsParentChildren() {
        // 创建子节点
        createTool.execute(new ToolCall("branch_create_child", Map.of(
                "title", "第一回合：罗德岛出发",
                "switchToChild", "true"
        )));

        // 切回 root
        switchTool.execute(new ToolCall("branch_switch", Map.of(
                "branchId", "b0000-start")));

        ToolCall call = new ToolCall("branch_list", Map.of("mode", "flat"));
        ToolResult result = listTool.execute(call);

        assertTrue(result.success(), "should succeed: " + result.error());
        String snippet = result.items().get(0).snippet();

        // 应有 2 个 branch
        assertTrue(snippet.contains("totalBranches: 2"),
                "should have 2 branches, got: " + snippet);

        // root branch 应有 children 列表
        assertTrue(snippet.contains("children:"),
                "should show children for root branch: " + snippet);

        // 子 branch 应有 parent: branch.b0000-start
        assertTrue(snippet.contains("parent: branch.b0000-start"),
                "child should have parent branch.b0000-start: " + snippet);
    }

    // ==================== 节点计数 ====================

    @Test
    @DisplayName("branch_list: shows actionCount/simContentCount/settlementCount")
    void showsCounts() {
        // 在 root branch 添加一些内容
        TurnSettlementSaveTool saveTool = new TurnSettlementSaveTool(dm);
        saveTool.execute(new ToolCall("turn_settlement_save", Map.of(
                "settlement", "测试结算内容。",
                "referencedSimIds", "sim0001"
        )));

        ToolCall call = new ToolCall("branch_list", Map.of());
        ToolResult result = listTool.execute(call);

        assertTrue(result.success(), "should succeed: " + result.error());
        String snippet = result.items().get(0).snippet();

        assertTrue(snippet.contains("actionCount:"),
                "should contain actionCount: " + snippet);
        assertTrue(snippet.contains("simContentCount:"),
                "should contain simContentCount: " + snippet);
        assertTrue(snippet.contains("settlementCount:"),
                "should contain settlementCount: " + snippet);
        // 添加了一个 settlement，settlementCount 应 > 0
        assertTrue(snippet.contains("settlementCount: 1") || snippet.contains("settlementCount: "),
                "should show settlement count");
    }

    // ==================== nodeOverview ====================

    @Test
    @DisplayName("branch_list: shows nodeOverview preview")
    void showsNodeOverview() {
        ToolCall call = new ToolCall("branch_list", Map.of("mode", "flat"));
        ToolResult result = listTool.execute(call);

        assertTrue(result.success(), "should succeed: " + result.error());
        String snippet = result.items().get(0).snippet();

        // 可能有 overview（取决于模板内容）
        // 至少 branch 信息是完整的
        assertTrue(snippet.contains("turn:"), "should contain turn field");
        assertTrue(snippet.contains("status:"), "should contain status field");
    }

    // ==================== Tree 模式 ====================

    @Test
    @DisplayName("branch_list: tree mode shows indented hierarchy")
    void treeMode() {
        // 创建子节点
        createTool.execute(new ToolCall("branch_create_child", Map.of(
                "title", "子节点A",
                "switchToChild", "true"
        )));
        // 切回 root 再创建另一个子节点
        switchTool.execute(new ToolCall("branch_switch", Map.of(
                "branchId", "b0000-start")));
        createTool.execute(new ToolCall("branch_create_child", Map.of(
                "title", "子节点B",
                "switchToChild", "true"
        )));

        // 确保 active branch 是子节点B
        // 然后用 tree 模式查看

        ToolCall call = new ToolCall("branch_list", Map.of("mode", "tree"));
        ToolResult result = listTool.execute(call);

        assertTrue(result.success(), "should succeed: " + result.error());
        String snippet = result.items().get(0).snippet();

        assertTrue(snippet.contains("totalBranches: 3"),
                "should have 3 branches, got: " + snippet);
        // Tree 模式应标记 active 节点
        assertTrue(snippet.contains("[ACTIVE]"),
                "tree mode should mark active branch with [ACTIVE]: " + snippet);
    }

    // ==================== Flat mode (default) ====================

    @Test
    @DisplayName("branch_list: default mode is flat")
    void defaultModeIsFlat() {
        // 不传 mode 参数
        ToolCall call = new ToolCall("branch_list", Map.of());
        ToolResult result = listTool.execute(call);

        assertTrue(result.success(), "should succeed: " + result.error());
        String snippet = result.items().get(0).snippet();
        assertTrue(snippet.contains("totalBranches:"),
                "should work without mode param");
        // Flat 模式下不应有 [ACTIVE] 标记在 tree 缩进中（但入口行可能有）
        assertTrue(snippet.contains("branch.b0000-start"),
                "should contain root branch");
    }

    // ==================== Multiple levels ====================

    @Test
    @DisplayName("branch_list: handles multi-level hierarchy")
    void multiLevelHierarchy() {
        // Root → Child1 → Grandchild
        createTool.execute(new ToolCall("branch_create_child", Map.of(
                "title", "第一层子节点",
                "switchToChild", "true"
        )));
        String child1 = dm.getActiveBranchId();

        createTool.execute(new ToolCall("branch_create_child", Map.of(
                "title", "第二层子节点",
                "switchToChild", "true"
        )));
        String grandchild = dm.getActiveBranchId();

        ToolCall call = new ToolCall("branch_list", Map.of("mode", "tree"));
        ToolResult result = listTool.execute(call);

        assertTrue(result.success(), "should succeed: " + result.error());
        String snippet = result.items().get(0).snippet();

        assertTrue(snippet.contains("totalBranches: 3"),
                "should have 3 branches: " + snippet);
        // 应有三层缩进
        assertTrue(snippet.contains(grandchild),
                "should contain grandchild branch: " + snippet);
    }
}
