package com.gsim.branch.tool;

import com.gsim.TestWorldFactory;
import com.gsim.data.DataManager;
import com.gsim.tool.ToolCall;
import com.gsim.tool.ToolResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 验证 branch_next_turn 创建子节点并切换 active branch 的原子操作。
 */
@DisplayName("BranchNextTurn 创建并切换")
class BranchNextTurnToolCreatesAndSwitchesTest {

    @TempDir Path tempDir;
    private DataManager dm;

    @BeforeEach
    void setUp() throws Exception {
        dm = TestWorldFactory.createWithDefaultRoot(tempDir);
    }

    @Test
    @DisplayName("调用后 activeBranch 切换到新节点")
    void switchesActiveBranchToNewNode() {
        String beforeBranch = dm.getActiveBranchId();
        BranchNextTurnTool tool = new BranchNextTurnTool(dm, null);

        ToolCall call = new ToolCall("branch_next_turn",
                java.util.Map.of("worldTime", "泰拉纪年1096年冬"));
        ToolResult result = tool.execute(call);

        assertTrue(result.success(), "Tool should succeed: " + result.error());
        String afterBranch = dm.getActiveBranchId();
        assertNotEquals(beforeBranch, afterBranch,
                "activeBranch should switch from " + beforeBranch + " to new child node");
        assertTrue(afterBranch.startsWith("branch.b"),
                "New active branch should follow branch.bXXXX naming");
    }

    @Test
    @DisplayName("创建后新节点是旧节点的子节点")
    void newBranchIsChildOfParent() {
        String parentBranch = dm.getActiveBranchId();
        BranchNextTurnTool tool = new BranchNextTurnTool(dm, null);

        ToolCall call = new ToolCall("branch_next_turn",
                java.util.Map.of("worldTime", "泰拉纪年1096年冬"));
        ToolResult result = tool.execute(call);

        assertTrue(result.success());
        // 检查返回的 snippet 包含正确的 parentBranchId
        String snippet = result.items().get(0).snippet();
        assertTrue(snippet.contains("parentBranchId=" + parentBranch),
                "Snippet should reference parent: " + snippet);
        assertTrue(snippet.contains("switched=true"), "Should report switched=true");
    }

    @Test
    @DisplayName("必须填写 worldTime")
    void requiresWorldTime() {
        BranchNextTurnTool tool = new BranchNextTurnTool(dm, null);

        ToolCall call = new ToolCall("branch_next_turn",
                java.util.Map.of("note", "some note"));
        ToolResult result = tool.execute(call);

        assertFalse(result.success(), "Should fail without worldTime");
        assertTrue(result.error().contains("worldTime is required"));
    }

    @Test
    @DisplayName("没有 active root 时返回错误")
    void failsWithoutActiveRoot() {
        DataManager emptyDm = new DataManager(tempDir.resolve("empty"));
        BranchNextTurnTool tool = new BranchNextTurnTool(emptyDm, null);

        ToolCall call = new ToolCall("branch_next_turn",
                java.util.Map.of("worldTime", "1096"));
        ToolResult result = tool.execute(call);

        assertFalse(result.success());
        assertTrue(result.error().contains("NO_ACTIVE_ROOT"));
    }

    @Test
    @DisplayName("onBranchChanged 回调被触发")
    void triggersOnBranchChanged() {
        AtomicBoolean callbackFired = new AtomicBoolean(false);
        BranchNextTurnTool tool = new BranchNextTurnTool(dm, () -> callbackFired.set(true));

        ToolCall call = new ToolCall("branch_next_turn",
                java.util.Map.of("worldTime", "泰拉纪年1096年冬"));
        ToolResult result = tool.execute(call);

        assertTrue(result.success());
        assertTrue(callbackFired.get(), "onBranchChanged callback should fire after switch");
    }
}
