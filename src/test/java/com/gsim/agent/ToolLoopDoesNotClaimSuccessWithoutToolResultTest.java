package com.gsim.agent;

import com.gsim.llm.FakeLlmManager;
import com.gsim.tool.AgentTool;
import com.gsim.tool.ToolCall;
import com.gsim.tool.ToolRegistry;
import com.gsim.tool.ToolResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 验证"禁止没有 tool_result 的成功宣称"守卫。
 * 适配 finish_action 架构：守卫从 post-loop 移动到 finish_action 验证中。
 *
 * <p>当 finish_action.message 包含"已保存/已创建/已切换/已写入"等关键词，
 * 但没有对应的工具执行记录时，finish_action 被拒绝，要求 LLM 先执行工具。
 */
@DisplayName("ToolLoop 禁止 finish_action 中无 tool_result 的成功宣称")
class ToolLoopDoesNotClaimSuccessWithoutToolResultTest {

    private FakeLlmManager fakeLlm;
    private ToolRegistry toolRegistry;
    private OrchestratorAgent agent;

    @BeforeEach
    void setUp() {
        fakeLlm = new FakeLlmManager();
        toolRegistry = new ToolRegistry();
        toolRegistry.register(new EchoTool());
        toolRegistry.register(new StubSaveLastResponseTool());
        toolRegistry.register(new StubBranchNextTurnTool());
        toolRegistry.register(new com.gsim.agent.tool.FinishActionTool());
        agent = new OrchestratorAgent(fakeLlm, toolRegistry, "test-model");
    }

    @Test
    @DisplayName("无工具执行时 finish_action 声称'已创建'被拒绝")
    void claimCreatedWithoutToolTriggersGuard() {
        // finish_action 声称成功，没有先调用任何工具 → 被拒绝
        fakeLlm.addResponse("{\"tool\":\"finish_action\",\"args\":{\"status\":\"success\","
                + "\"message\":\"第一回合节点已创建，当前切换到 branch.b0001-first-turn。这是开始序言...\"}}");
        // 重试：干净的 finish_action
        fakeLlm.addResponse("{\"tool\":\"finish_action\",\"args\":{\"status\":\"success\","
                + "\"message\":\"我尚未执行创建操作。请告知具体需求。\"}}");

        var result = agent.chatWithContextSession(
                "# Base\nbranch: branch.b0000-start\n",
                List.of(), "创建第一回合");

        assertTrue(result.success());
        // 第一次 finish_action 被拒绝，第二次成功
        assertEquals(2, result.toolCalls().size(),
                "Two finish_action calls: first rejected, second accepted");
        assertEquals("finish_action", result.toolCalls().get(0).tool());
        assertEquals("finish_action", result.toolCalls().get(1).tool());
        assertFalse(result.finalText().contains("已创建"),
                "Accepted finish_action message should not contain unbacked claim");
    }

    @Test
    @DisplayName("无工具执行时 finish_action 声称'已保存'被拒绝")
    void claimSavedWithoutToolTriggersGuard() {
        fakeLlm.addResponse("{\"tool\":\"finish_action\",\"args\":{\"status\":\"success\","
                + "\"message\":\"序章已保存，数据已入库。一切就绪。\"}}");
        fakeLlm.addResponse("{\"tool\":\"finish_action\",\"args\":{\"status\":\"success\","
                + "\"message\":\"很抱歉，我还没有真正保存数据。请告知要保存什么。\"}}");

        var result = agent.chatWithContextSession(
                "# Base\nbranch: branch.b0000-start\n",
                List.of(), "保存序章");

        assertTrue(result.success());
        assertEquals(2, result.toolCalls().size());
    }

    @Test
    @DisplayName("无工具执行时 finish_action 声称'已切换'被拒绝")
    void claimSwitchedWithoutToolTriggersGuard() {
        fakeLlm.addResponse("{\"tool\":\"finish_action\",\"args\":{\"status\":\"success\","
                + "\"message\":\"已切换到 branch.b0002，当前活跃分支已更新。\"}}");
        fakeLlm.addResponse("{\"tool\":\"finish_action\",\"args\":{\"status\":\"success\","
                + "\"message\":\"我尚未执行切换操作。当前仍在 branch.b0000-start。\"}}");

        var result = agent.chatWithContextSession(
                "# Base\nbranch: branch.b0000-start\n",
                List.of(), "切换分支");

        assertTrue(result.success());
        assertEquals(2, result.toolCalls().size());
    }

    @Test
    @DisplayName("有工具执行时 finish_action 声称'已保存'通过验证（合法场景）")
    void claimCreatedWithToolBackingDoesNotTriggerGuard() {
        // 先调用 save 工具
        fakeLlm.addResponse("{\"tool\":\"turn_settlement_save_last_response\","
                + "\"args\":{\"inputSummary\":\"测试结算\"}}");
        // 然后 finish_action 声称已保存 → 有对应工具 support，合法
        fakeLlm.addResponse("{\"tool\":\"finish_action\",\"args\":{\"status\":\"success\","
                + "\"message\":\"结算已保存为 stl0001。当前状态正常。\"}}");

        var result = agent.chatWithContextSession(
                "# Base\nbranch: branch.b0000-start\n",
                List.of(), "保存结算");

        assertTrue(result.success());
        assertEquals(2, result.toolCalls().size(),
                "save tool + finish_action both accepted");
        assertTrue(result.finalText().contains("stl0001"),
                "Accepted message should include save result");
    }

    @Test
    @DisplayName("纯自然语言 finish_action.message 不包含成功宣称时通过验证")
    void normalResponseWithoutClaimsDoesNotTriggerGuard() {
        fakeLlm.addResponse("{\"tool\":\"finish_action\",\"args\":{\"status\":\"success\","
                + "\"message\":\"当前系统状态正常。branch.b0000-start 是活跃的根分支。你可以使用命令来创建新的分支节点。\"}}");

        var result = agent.chatWithContextSession(
                "# Base\nbranch: branch.b0000-start\n",
                List.of(), "查看状态");

        assertTrue(result.success());
        assertEquals(1, result.toolCalls().size(),
                "Single finish_action, no rejection");
        assertTrue(result.finalText().contains("branch.b0000-start"),
                "Normal response should be returned as-is");
    }

    @Test
    @DisplayName("guardSuccessClaimWithoutToolBacking 单元测试：各种成功关键词")
    void guardUnitTestAllClaimKeywords() {
        // 测试所有被检测的成功关键词（静态方法仍存在，用于后处理防御）
        String[] claims = {"已保存完毕。", "已创建完成。", "已切换到新分支。",
                "已入库存储。", "已写入文件。", "已更新数据。", "已完成操作。"};

        for (String claim : claims) {
            String guarded = OrchestratorAgent.guardSuccessClaimWithoutToolBacking(
                    claim, List.of(), 0);
            assertTrue(guarded.contains("[系统提示]"),
                    "Should guard claim: " + claim);
        }

        // 有工具执行时全部通过
        var toolCalls = List.of(new OrchestratorAgent.ToolCallRecord(
                "echo", java.util.Map.of(), ToolResult.ok("echo", List.of())));
        for (String claim : claims) {
            String guarded = OrchestratorAgent.guardSuccessClaimWithoutToolBacking(
                    claim, toolCalls, 1);
            assertFalse(guarded.contains("[系统提示]"),
                    "Should NOT guard when tool backed: " + claim);
        }
    }

    @Test
    @DisplayName("validateFinishActionClaims 无工具时检测成功关键词")
    void validateFinishActionClaimsDetectsUnbackedClaims() {
        // 直接测试 finish_action 验证方法
        String error = OrchestratorAgent.validateFinishActionClaims(
                "第一回合节点已创建，当前已切换。", List.of());
        assertNotNull(error, "Should detect unbacked claims");
        assertTrue(error.contains("没有执行任何工具"),
                "Error should mention no tools executed: " + error);
    }

    @Test
    @DisplayName("validateFinishActionClaims 有匹配工具时返回 null（通过）")
    void validateFinishActionClaimsPassesWithMatchingTools() {
        var toolCalls = List.of(
                new OrchestratorAgent.ToolCallRecord(
                        "turn_settlement_save_last_response",
                        Map.of("inputSummary", "test"),
                        ToolResult.ok("turn_settlement_save_last_response", List.of())),
                new OrchestratorAgent.ToolCallRecord(
                        "branch_next_turn",
                        Map.of("worldTime", "1096"),
                        ToolResult.ok("branch_next_turn", List.of()))
        );

        String error = OrchestratorAgent.validateFinishActionClaims(
                "结算已保存为 stl0001，已进入 branch.b0002。", toolCalls);
        assertNull(error, "Should pass when claims are backed by tools");
    }

    // ===== Fake Tools =====

    static class EchoTool implements AgentTool {
        @Override public String name() { return "echo"; }
        @Override public String description() { return "Echo tool."; }
        @Override
        public ToolResult execute(ToolCall call) {
            return ToolResult.ok(name(), List.of(
                    new ToolResult.Item("echo", "test", "ok", 1.0)));
        }
    }

    static class StubSaveLastResponseTool implements AgentTool {
        @Override public String name() { return "turn_settlement_save_last_response"; }
        @Override public String description() { return "Save last assistant response as settlement."; }
        @Override
        public ToolResult execute(ToolCall call) {
            return ToolResult.ok(name(), List.of(
                    new ToolResult.Item("Settlement stl0001", "stl0001",
                            "status=OK\nsettlementId=stl0001\n", 1.0)));
        }
    }

    static class StubBranchNextTurnTool implements AgentTool {
        @Override public String name() { return "branch_next_turn"; }
        @Override public String description() { return "Create next turn and switch."; }
        @Override
        public ToolResult execute(ToolCall call) {
            return ToolResult.ok(name(), List.of(
                    new ToolResult.Item("Next turn: branch.b0002", "branch.b0002",
                            "status=OK\ncreatedBranchId=branch.b0002\nswitched=true\n", 1.0)));
        }
    }
}
