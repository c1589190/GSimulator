package com.gsim.branch.tool;

import com.gsim.TestWorldFactory;
import com.gsim.data.DataManager;
import com.gsim.tool.ToolCall;
import com.gsim.tool.ToolResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("TurnSettlementGetTool")
class TurnSettlementGetToolTest {

    @TempDir Path tempDir;
    private DataManager dm;
    private TurnSettlementGetTool tool;

    @BeforeEach
    void setUp() throws Exception {
        dm = TestWorldFactory.createWithDefaultRoot(tempDir);
        tool = new TurnSettlementGetTool(dm);

        // 在 branch 文件中写入带 versioned settlement 的内容
        Path branchFile = tempDir.resolve("worlds").resolve("default").resolve("branches")
                .resolve("b0000-start.md");
        String branchContent = """
                ## 八、回合结算

                <!-- TURN_SETTLEMENT:stl0001 START -->
                - settlementId：stl0001
                - revisionOf：none
                **本回合输入摘要：**初版结算
                **完整回合结算：**
                这是第一版回合结算正文。包含初始推演结果。
                **关联推演内容：**
                **关联玩家行动：**
                <!-- TURN_SETTLEMENT:stl0001 END -->

                <!-- TURN_SETTLEMENT:stl0002 START -->
                - settlementId：stl0002
                - revisionOf：stl0001
                **本回合输入摘要：**修订版结算
                **完整回合结算：**
                这是第二版回合结算正文。修正了第一版的错误。
                **关联推演内容：**sim0001
                **关联玩家行动：**act0001,act0002
                <!-- TURN_SETTLEMENT:stl0002 END -->
                """;
        Files.writeString(branchFile, branchContent);
    }

    @Nested
    @DisplayName("不传 settlementId — 保持旧行为")
    class LatestKeepsOldBehavior {
        @Test
        @DisplayName("返回 settlement 列表 + 最新结算正文")
        void returnsLatestSettlement() {
            var call = new ToolCall(TurnSettlementGetTool.NAME, Map.of());
            ToolResult result = tool.execute(call);
            assertTrue(result.success(), "tool should succeed: " + result.error());
            String content = result.items().get(0).snippet();
            assertTrue(content.contains("settlementCount: 2"), content);
            assertTrue(content.contains("stl0001"), content);
            assertTrue(content.contains("stl0002"), content);
            assertTrue(content.contains("第二版回合结算正文"), "should return latest settlement: " + content);
        }
    }

    @Nested
    @DisplayName("传 settlementId — 按 ID 读取历史版本")
    class BySettlementId {
        @Test
        @DisplayName("settlementId=stl0001 返回第一版正文")
        void getBySettlementId() {
            var call = new ToolCall(TurnSettlementGetTool.NAME,
                    Map.of("settlementId", "stl0001"));
            ToolResult result = tool.execute(call);
            assertTrue(result.success(), "tool should succeed: " + result.error());
            String content = result.items().get(0).snippet();
            assertTrue(content.contains("settlementId: stl0001"), content);
            assertTrue(content.contains("revisionOf: none"), content);
            assertTrue(content.contains("第一版回合结算正文"), content);
        }

        @Test
        @DisplayName("settlementId=stl0002 返回修订版正文及 metadata")
        void getRevisionSettlement() {
            var call = new ToolCall(TurnSettlementGetTool.NAME,
                    Map.of("settlementId", "stl0002"));
            ToolResult result = tool.execute(call);
            assertTrue(result.success(), "tool should succeed: " + result.error());
            String content = result.items().get(0).snippet();
            assertTrue(content.contains("settlementId: stl0002"), content);
            assertTrue(content.contains("revisionOf: stl0001"), content);
            assertTrue(content.contains("referencedSimIds: sim0001"), content);
            assertTrue(content.contains("referencedActionIds: act0001,act0002"), content);
            assertTrue(content.contains("第二版回合结算正文"), content);
        }

        @Test
        @DisplayName("不存在的 settlementId 返回 SETTLEMENT_NOT_FOUND")
        void missingSettlementIdReturnsNotFound() {
            var call = new ToolCall(TurnSettlementGetTool.NAME,
                    Map.of("settlementId", "stl9999"));
            ToolResult result = tool.execute(call);
            assertFalse(result.success());
            assertTrue(result.error().contains("SETTLEMENT_NOT_FOUND"));
        }
    }

    @Nested
    @DisplayName("BranchNodeGetTool 分页")
    class BranchNodeGetPagination {
        @Test
        @DisplayName("mode=full 支持分页参数")
        void fullModePagination() throws Exception {
            var store = new com.gsim.chat.BranchMessageStore(dm, tempDir);
            var nodeGetTool = new com.gsim.context.memory.BranchNodeGetTool(dm, store);

            var call = new ToolCall("branch_node_get", Map.of(
                    "nodeId", "branch.b0000-start", "mode", "full",
                    "offset", "0", "limit", "200"));
            ToolResult result = nodeGetTool.execute(call);
            assertTrue(result.success());
            String content = result.items().get(0).snippet();
            assertTrue(content.contains("originalLength:"));
            assertTrue(content.contains("truncated:"));
            assertTrue(content.contains("returnedRange:"));
        }

        @Test
        @DisplayName("mode=full truncated=true 时显示截断提示")
        void fullModeTruncatedMetadata() throws Exception {
            var store = new com.gsim.chat.BranchMessageStore(dm, tempDir);
            var nodeGetTool = new com.gsim.context.memory.BranchNodeGetTool(dm, store);

            var call = new ToolCall("branch_node_get", Map.of(
                    "nodeId", "branch.b0000-start", "mode", "full",
                    "limit", "50"));
            ToolResult result = nodeGetTool.execute(call);
            assertTrue(result.success());
            String content = result.items().get(0).snippet();
            assertTrue(content.contains("truncated: true"));
            assertTrue(content.contains("已截断"));
        }

        @Test
        @DisplayName("mode=full full=true 返回全文")
        void fullModeFull() throws Exception {
            var store = new com.gsim.chat.BranchMessageStore(dm, tempDir);
            var nodeGetTool = new com.gsim.context.memory.BranchNodeGetTool(dm, store);

            var call = new ToolCall("branch_node_get", Map.of(
                    "nodeId", "branch.b0000-start", "mode", "full",
                    "full", "true"));
            ToolResult result = nodeGetTool.execute(call);
            assertTrue(result.success());
            String content = result.items().get(0).snippet();
            assertTrue(content.contains("returnedRange: 0-"));
        }
    }
}
