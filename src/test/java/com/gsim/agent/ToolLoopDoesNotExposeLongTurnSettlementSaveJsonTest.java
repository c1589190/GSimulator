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
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 验证使用 turn_settlement_save_last_response 后，
 * 最终回复不含 raw JSON、含大量正文的工具调用泄漏、[工具结果] 或成功宣称警告。
 * 适配 finish_action 架构。
 */
@DisplayName("ToolLoop 不暴露长篇结算 save JSON (finish_action)")
class ToolLoopDoesNotExposeLongTurnSettlementSaveJsonTest {

    private FakeLlmManager fakeLlm;
    private ToolRegistry toolRegistry;
    private OrchestratorAgent agent;

    @BeforeEach
    void setUp() {
        fakeLlm = new FakeLlmManager();
        toolRegistry = new ToolRegistry();
        toolRegistry.register(new StubSaveLastResponseTool());
        toolRegistry.register(new StubBranchNextTurnTool());
        toolRegistry.register(new com.gsim.agent.tool.FinishActionTool());
        agent = new OrchestratorAgent(fakeLlm, toolRegistry, "test-model");
    }

    @Test
    @DisplayName("最终回复不含 fenced JSON")
    void finalTextHasNoFencedJson() {
        // Round 1: 结算正文 + fenced save tool call
        fakeLlm.addResponse("## 第一回合结算\n\n### 概述\n本回合...\n\n" +
                "```json\n" +
                "{\"tool\":\"turn_settlement_save_last_response\"," +
                "\"args\":{\"inputSummary\":\"玩家A抵达龙门\"}}\n" +
                "```");
        // Round 2: branch_next_turn
        fakeLlm.addResponse("{\"tool\":\"branch_next_turn\"," +
                "\"args\":{\"worldTime\":\"泰拉纪年1096年冬\"}}");
        // Round 3: finish_action
        fakeLlm.addResponse("{\"tool\":\"finish_action\",\"args\":{\"status\":\"success\","
                + "\"message\":\"第一回合结算已保存为 stl0001。已进入第二回合 branch.b0002。\"}}");

        var result = agent.chatWithContextSession(
                "# Base\nbranch: branch.b0000-start\n",
                List.of(), "结算本回合并进入下一回合");

        assertTrue(result.success());
        String ft = result.finalText();
        assertFalse(ft.contains("```json"), "Final text should NOT contain fenced JSON block");
        assertFalse(ft.contains("```"), "Final text should NOT contain any fence marker");
    }

    @Test
    @DisplayName("最终回复不含裸 JSON tool call")
    void finalTextHasNoBareJsonToolCall() {
        // Round 1: settlement + save
        fakeLlm.addResponse("结算正文...\n\n```json\n" +
                "{\"tool\":\"turn_settlement_save_last_response\"," +
                "\"args\":{\"inputSummary\":\"test\"}}\n```");
        // Round 2: next turn
        fakeLlm.addResponse("{\"tool\":\"branch_next_turn\"," +
                "\"args\":{\"worldTime\":\"1096\"}}");
        // Round 3: finish_action
        fakeLlm.addResponse("{\"tool\":\"finish_action\",\"args\":{\"status\":\"success\","
                + "\"message\":\"完成。结算 stl0001，进入 branch.b0002。\"}}");

        var result = agent.chatWithContextSession(
                "# Base\nbranch: branch.b0000-start\n",
                List.of(), "结算+下一回合");

        assertTrue(result.success());
        String ft = result.finalText();
        assertFalse(ft.contains("\"tool\""), "Final text should NOT contain bare JSON tool call");
        assertFalse(ft.contains("\"args\""), "Final text should NOT contain JSON args");
    }

    @Test
    @DisplayName("最终回复不含 [工具结果] 伪造标记")
    void finalTextHasNoFakeBracketResult() {
        fakeLlm.addResponse("结算正文...\n\n```json\n" +
                "{\"tool\":\"turn_settlement_save_last_response\"," +
                "\"args\":{\"inputSummary\":\"test\"}}\n```");
        fakeLlm.addResponse("{\"tool\":\"branch_next_turn\"," +
                "\"args\":{\"worldTime\":\"1096\"}}");
        fakeLlm.addResponse("{\"tool\":\"finish_action\",\"args\":{\"status\":\"success\","
                + "\"message\":\"完成。\"}}");

        var result = agent.chatWithContextSession(
                "# Base\nbranch: branch.b0000-start\n",
                List.of(), "结算+下一回合");

        assertTrue(result.success());
        assertFalse(result.finalText().contains("[工具结果]"),
                "Final text should NOT contain [工具结果]");
    }

    @Test
    @DisplayName("最终回复不含成功宣称警告（有工具支撑）")
    void finalTextHasNoSuccessClaimWarning() {
        fakeLlm.addResponse("结算正文...\n\n```json\n" +
                "{\"tool\":\"turn_settlement_save_last_response\"," +
                "\"args\":{\"inputSummary\":\"test\"}}\n```");
        fakeLlm.addResponse("{\"tool\":\"branch_next_turn\"," +
                "\"args\":{\"worldTime\":\"1096\"}}");
        fakeLlm.addResponse("{\"tool\":\"finish_action\",\"args\":{\"status\":\"success\","
                + "\"message\":\"结算已保存为 stl0001，已进入 branch.b0002。\"}}");

        var result = agent.chatWithContextSession(
                "# Base\nbranch: branch.b0000-start\n",
                List.of(), "结算并进入下一回合");

        assertTrue(result.success());
        String ft = result.finalText();
        assertFalse(ft.contains("[系统提示]"),
                "Should NOT have system warning when tools back claims: " + ft);
        assertFalse(ft.contains("未检测到对应的工具执行记录"),
                "Should NOT have unbacked tool claim warning");
    }

    @Test
    @DisplayName("LLM 试图用 turn_settlement_save 传长 JSON → 工具执行后 finish_action 结束")
    void longJsonInOldSaveToolIsStripped() {
        // 模拟 LLM 使用旧 turn_settlement_save 传长 settlement → embedded in fenced JSON
        fakeLlm.addResponse("结算完成。\n\n```json\n" +
                "{\"tool\":\"turn_settlement_save\"," +
                "\"args\":{\"settlement\":\"长文本内容...\"}}\n" +
                "```\n\n已进入下一回合。");
        fakeLlm.addResponse("{\"tool\":\"finish_action\",\"args\":{\"status\":\"success\","
                + "\"message\":\"结算操作已执行。下一步需要进入下一回合。\"}}");

        var result = agent.chatWithContextSession(
                "# Base\nbranch: branch.b0000-start\n",
                List.of(), "结算");

        assertTrue(result.success());
        String ft = result.finalText();
        // 即使 LLM 泄露了长 settlement JSON，finish_action.message 不应包含它
        assertFalse(ft.contains("turn_settlement_save"),
                "Leaked long settlement JSON should be stripped from finalText");
    }

    @Test
    @DisplayName("工具执行时 draft 是干净的结算正文（通过 stub 捕获）")
    void draftIsCleanSettlementText() {
        // 用自己的 agent + stub，通过 Supplier 延迟捕获工具执行时的 draft
        var capturedDraft = new java.util.concurrent.atomic.AtomicReference<String>();
        var localFakeLlm = new FakeLlmManager();
        var localRegistry = new ToolRegistry();

        // 注册会捕获 draft 的 SaveLastResponse stub（Supplier 延迟求值）
        OrchestratorAgent[] agentHolder = new OrchestratorAgent[1];
        localRegistry.register(new AgentTool() {
            @Override public String name() { return "turn_settlement_save_last_response"; }
            @Override public String description() { return "Save last response."; }
            @Override
            public ToolResult execute(ToolCall call) {
                // 延迟获取 draft：此时 agent 已初始化
                String draft = agentHolder[0] != null
                        ? agentHolder[0].getLastAssistantDraft() : "";
                capturedDraft.set(draft);
                return ToolResult.ok(name(), List.of(
                        new ToolResult.Item("ok", "b1", "status=OK", 1.0)));
            }
        });
        localRegistry.register(new StubBranchNextTurnTool());
        localRegistry.register(new com.gsim.agent.tool.FinishActionTool());

        // 创建 agent（此时 agent 引用对 stub 可见）
        var localAgent = new OrchestratorAgent(localFakeLlm, localRegistry, "test-model");
        agentHolder[0] = localAgent;

        localFakeLlm.addResponse("结算正文——完整内容包含玩家行动和状态变化...\n\n" +
                "```json\n" +
                "{\"tool\":\"turn_settlement_save_last_response\"," +
                "\"args\":{\"inputSummary\":\"摘要\"}}\n" +
                "```");
        localFakeLlm.addResponse("{\"tool\":\"branch_next_turn\"," +
                "\"args\":{\"worldTime\":\"1096\"}}");
        localFakeLlm.addResponse("{\"tool\":\"finish_action\",\"args\":{\"status\":\"success\","
                + "\"message\":\"完成。\"}}");

        var result = localAgent.chatWithContextSession(
                "# Base\nbranch: branch.b0000-start\n",
                List.of(), "结算并进入下一回合");

        assertTrue(result.success());
        String draft = capturedDraft.get();
        assertNotNull(draft, "Tool should have captured draft during execution");
        assertTrue(draft.contains("结算正文"), "Draft should contain settlement text, got: " + draft);
        assertFalse(draft.contains("\"tool\""), "Draft should be clean of JSON");
        assertFalse(draft.contains("```json"), "Draft should be clean of fences");
        assertFalse(draft.contains("[系统提示]"), "Draft should be clean of warnings");
    }

    // ===== Stubs =====

    static class StubSaveLastResponseTool implements AgentTool {
        @Override public String name() { return "turn_settlement_save_last_response"; }
        @Override public String description() { return "Save last assistant response as settlement."; }
        @Override
        public ToolResult execute(ToolCall call) {
            return ToolResult.ok(name(), List.of(
                    new ToolResult.Item("Settlement stl0001", "branch.b0001",
                            "status=OK\nsettlementId=stl0001\nsavedFrom=lastAssistantDraft\n",
                            1.0)));
        }
    }

    static class StubBranchNextTurnTool implements AgentTool {
        @Override public String name() { return "branch_next_turn"; }
        @Override public String description() { return "Create next turn and switch."; }
        @Override
        public ToolResult execute(ToolCall call) {
            return ToolResult.ok(name(), List.of(
                    new ToolResult.Item("Next turn: branch.b0002", "branch.b0002",
                            "status=OK\ncreatedBranchId=branch.b0002\n" +
                                    "activeBranchId=branch.b0002\nswitched=true\n",
                            1.0)));
        }
    }
}
