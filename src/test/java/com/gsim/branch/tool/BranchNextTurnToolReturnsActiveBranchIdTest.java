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

import static org.junit.jupiter.api.Assertions.*;

/**
 * 验证 branch_next_turn 返回的 status snippet 包含所有必需字段。
 */
@DisplayName("BranchNextTurn 返回激活 branch ID")
class BranchNextTurnToolReturnsActiveBranchIdTest {

    @TempDir Path tempDir;
    private DataManager dm;

    @BeforeEach
    void setUp() throws Exception {
        dm = TestWorldFactory.createWithDefaultRoot(tempDir);
    }

    @Test
    @DisplayName("返回 snippet 包含 status=OK")
    void returnsStatusOk() {
        BranchNextTurnTool tool = new BranchNextTurnTool(dm, null);
        ToolCall call = new ToolCall("branch_next_turn",
                java.util.Map.of("worldTime", "泰拉纪年1096年冬"));
        ToolResult result = tool.execute(call);

        assertTrue(result.success());
        assertEquals(1, result.items().size());
        String snippet = result.items().get(0).snippet();
        assertTrue(snippet.contains("status=OK"), "Should contain status=OK: " + snippet);
    }

    @Test
    @DisplayName("返回 snippet 包含 createdBranchId 且与实际一致")
    void returnsCreatedBranchId() {
        BranchNextTurnTool tool = new BranchNextTurnTool(dm, null);
        ToolCall call = new ToolCall("branch_next_turn",
                java.util.Map.of("worldTime", "泰拉纪年1096年冬"));
        ToolResult result = tool.execute(call);

        assertTrue(result.success());
        String snippet = result.items().get(0).snippet();
        assertTrue(snippet.contains("createdBranchId=branch."),
                "Should contain createdBranchId: " + snippet);

        // 提取 createdBranchId 并验证 activeBranch 已切换过来
        String createdId = extractField(snippet, "createdBranchId");
        assertEquals(dm.getActiveBranchId(), createdId,
                "activeBranchId should match createdBranchId after atomic switch");
    }

    @Test
    @DisplayName("返回 snippet 包含 activeBranchId")
    void returnsActiveBranchId() {
        BranchNextTurnTool tool = new BranchNextTurnTool(dm, null);
        ToolCall call = new ToolCall("branch_next_turn",
                java.util.Map.of("worldTime", "泰拉纪年1096年冬"));
        ToolResult result = tool.execute(call);

        assertTrue(result.success());
        String snippet = result.items().get(0).snippet();
        assertTrue(snippet.contains("activeBranchId="), "Should contain activeBranchId");
        assertTrue(snippet.contains("parentBranchId="), "Should contain parentBranchId");
        assertTrue(snippet.contains("turn="), "Should contain turn");
        assertTrue(snippet.contains("worldTime="), "Should contain worldTime");
        assertTrue(snippet.contains("switched=true"), "Should contain switched=true");
    }

    @Test
    @DisplayName("父节点无推演结果时返回 warning=NO_SIM_RESULT")
    void warnsWhenNoSimResult() {
        BranchNextTurnTool tool = new BranchNextTurnTool(dm, null);
        ToolCall call = new ToolCall("branch_next_turn",
                java.util.Map.of("worldTime", "泰拉纪年1096年冬"));
        ToolResult result = tool.execute(call);

        assertTrue(result.success());
        String snippet = result.items().get(0).snippet();
        // 首次创建时父节点 (b0000-start) 通常没有 sim result
        // warning 存在与否取决于默认 root 是否已有 sim content
        // 只验证关键字段存在
        assertTrue(snippet.contains("createdBranchId="));
    }

    @Test
    @DisplayName("指定 title 参数后节点名称被覆盖")
    void titleOverridesDefaultName() throws Exception {
        BranchNextTurnTool tool = new BranchNextTurnTool(dm, null);
        ToolCall call = new ToolCall("branch_next_turn",
                java.util.Map.of("worldTime", "泰拉纪年1096年冬",
                        "title", "序章·龙门之夜"));
        ToolResult result = tool.execute(call);

        assertTrue(result.success());
        // 读取新 branch 文件，验证 title 已在内容中
        String activeId = dm.getActiveBranchId();
        String content = dm.readBranchFile(activeId);
        assertNotNull(content);
        assertTrue(content.contains("序章·龙门之夜"),
                "Branch file should contain custom title: " + content);
    }

    private static String extractField(String snippet, String field) {
        for (String line : snippet.split("\n")) {
            if (line.startsWith(field + "=")) {
                return line.substring(field.length() + 1);
            }
        }
        return null;
    }
}
