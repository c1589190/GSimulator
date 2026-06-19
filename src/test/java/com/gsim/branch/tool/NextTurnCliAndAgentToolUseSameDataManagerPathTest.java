package com.gsim.branch.tool;

import com.gsim.TestWorldFactory;
import com.gsim.data.DataDocument;
import com.gsim.data.DataManager;
import com.gsim.tool.ToolCall;
import com.gsim.tool.ToolResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 验证 CLI /nextturn 和 Agent branch_next_turn 工具
 * 使用相同的 DataManager.createNextTurnBranch() 路径。
 *
 * <p>确保两条路径的行为一致：
 * <ul>
 *   <li>创建子节点</li>
 *   <li>下一代 branch ID（如 b0001）</li>
 *   <li>切换 active branch</li>
 *   <li>写入相同格式的 branch markdown 文件</li>
 * </ul>
 */
@DisplayName("CLI /nextturn 与 Agent tool 共用 DataManager 路径")
class NextTurnCliAndAgentToolUseSameDataManagerPathTest {

    @TempDir Path tempDir;
    private DataManager dm;

    @BeforeEach
    void setUp() throws Exception {
        dm = TestWorldFactory.createWithDefaultRoot(tempDir);
    }

    @Test
    @DisplayName("branch_next_turn 调用 dm.createNextTurnBranch 创建子节点")
    void branchNextTurnCallsDataManagerCreateNextTurnBranch() {
        String parentBranch = dm.getActiveBranchId();
        BranchNextTurnTool tool = new BranchNextTurnTool(dm, null);

        ToolCall call = new ToolCall("branch_next_turn",
                java.util.Map.of("worldTime", "泰拉纪年1096年冬", "note", "test note"));
        ToolResult result = tool.execute(call);

        assertTrue(result.success());
        String newBranch = dm.getActiveBranchId();
        assertNotEquals(parentBranch, newBranch,
                "active branch should change after next turn");
    }

    @Test
    @DisplayName("CLI 路径（直接调用 dm.createNextTurnBranch）与 tool 路径产出相同结构")
    void cliAndToolProduceSameBranchStructure() throws Exception {
        // 使用 CLI 路径直接调用
        String beforeCli = dm.getActiveBranchId();
        DataDocument cliDoc = dm.createNextTurnBranch("泰拉纪年1096年冬", "cli note");
        String cliBranch = dm.getActiveBranchId();

        assertNotEquals(beforeCli, cliBranch);
        assertNotNull(cliDoc);
        assertEquals(cliBranch, cliDoc.id());

        // 验证 branch 文件存在且包含关键字段
        String cliContent = dm.readBranchFile(cliBranch);
        assertNotNull(cliContent);
        assertTrue(cliContent.contains("parent: " + beforeCli),
                "Branch file should reference parent: " + cliContent);
        assertTrue(cliContent.contains("world_time: 泰拉纪年1096年冬"),
                "Branch file should contain world_time");
    }

    @Test
    @DisplayName("通过 tool 执行后再直接调用 dm.createNextTurnBranch，branch ID 连续递增")
    void branchIdIncrementsContinuouslyAcrossCliAndTool() throws Exception {
        // 通过 tool 创建 b0001
        BranchNextTurnTool tool = new BranchNextTurnTool(dm, null);
        ToolCall call1 = new ToolCall("branch_next_turn",
                java.util.Map.of("worldTime", "1096"));
        ToolResult result1 = tool.execute(call1);
        assertTrue(result1.success());
        String toolBranch = dm.getActiveBranchId();

        // 直接调用 dm.createNextTurnBranch 创建下一个
        DataDocument cliDoc = dm.createNextTurnBranch("1097", "direct call");
        String cliBranch = dm.getActiveBranchId();

        // 验证 ID 递增
        assertNotEquals(toolBranch, cliBranch);
        String toolNum = toolBranch.replace("branch.b", "");
        String cliNum = cliBranch.replace("branch.b", "");
        int tn = Integer.parseInt(toolNum);
        int cn = Integer.parseInt(cliNum);
        assertEquals(tn + 1, cn,
                "Branch IDs should increment sequentially: " + toolBranch + " → " + cliBranch);
    }

    @Test
    @DisplayName("tool 和 CLI 路径创建的 branch 文件都有相同的 front matter 字段")
    void bothPathsCreateSameFrontMatterFields() throws Exception {
        // CLI 路径
        DataDocument cliDoc = dm.createNextTurnBranch("1096冬", "cli");
        String cliContent = dm.readBranchFile(cliDoc.id());
        assertNotNull(cliContent);

        // Tool 路径（第二次调用）
        BranchNextTurnTool tool = new BranchNextTurnTool(dm, null);
        ToolCall call = new ToolCall("branch_next_turn",
                java.util.Map.of("worldTime", "1097春", "note", "tool"));
        ToolResult result = tool.execute(call);
        assertTrue(result.success());
        String toolContent = dm.readBranchFile(dm.getActiveBranchId());
        assertNotNull(toolContent);

        // 两者都应该包含相同的结构字段
        for (String field : new String[]{"id:", "name:", "parent:", "turn:", "world_time:", "updated:"}) {
            assertTrue(cliContent.contains(field),
                    "CLI path should have field '" + field + "'");
            assertTrue(toolContent.contains(field),
                    "Tool path should have field '" + field + "'");
        }
    }

    @Test
    @DisplayName("tool 路径的 onBranchChanged 回调对标 CLI /nextturn 后的 ContextSession reset")
    void onBranchChangedReflectsCliPostSwitchBehavior() throws Exception {
        java.util.concurrent.atomic.AtomicBoolean callbackCalled =
                new java.util.concurrent.atomic.AtomicBoolean(false);

        BranchNextTurnTool tool = new BranchNextTurnTool(dm, () -> callbackCalled.set(true));
        ToolCall call = new ToolCall("branch_next_turn",
                java.util.Map.of("worldTime", "1096"));
        ToolResult result = tool.execute(call);

        assertTrue(result.success());
        assertTrue(callbackCalled.get(),
                "onBranchChanged should be called, mirroring CLI /nextturn's post-switch behavior");
    }
}
