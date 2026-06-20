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
 * 验证「结算 + 下一回合」完整流程：
 * 1. Agent 生成长篇结算正文 + 嵌入 fenced tool call
 * 2. ToolLoop 剥离 fenced JSON 后缓存为 draft
 * 3. turn_settlement_save_last_response 从 draft 读取并保存
 * 4. 保存成功后调用 branch_next_turn
 * 5. 最终回复不含 raw JSON / [工具结果] / 系统警告
 */
@DisplayName("Agent 结算后进入下一回合完整流程")
class AgentSettlementThenNextTurnUsesSaveLastResponseAndBranchNextTurnTest {

    private FakeLlmManager fakeLlm;
    private ToolRegistry toolRegistry;
    private OrchestratorAgent agent;
    private AtomicReference<String> savedSettlement;
    private AtomicReference<Boolean> nextTurnCalled;

    @BeforeEach
    void setUp() {
        fakeLlm = new FakeLlmManager();
        toolRegistry = new ToolRegistry();
        savedSettlement = new AtomicReference<>("");
        nextTurnCalled = new AtomicReference<>(false);

        toolRegistry.register(new StubSaveLastResponseTool(savedSettlement,
                () -> agent != null ? agent.getLastAssistantDraft() : ""));
        toolRegistry.register(new StubBranchNextTurnTool(nextTurnCalled));
        toolRegistry.register(new com.gsim.agent.tool.FinishActionTool());

        agent = new OrchestratorAgent(fakeLlm, toolRegistry, "test-model");
    }

    @Test
    @DisplayName("完整流程：生成结算+save_last_response → branch_next_turn → 最终回复")
    void fullSettlementThenNextTurnFlow() {
        // Round 1: LLM 输出结算正文 + 嵌入 fenced tool call（save_last_response）
        // 这是关键设计：长篇正文在自然语言中，工具调用在 fenced JSON 块中
        String round1 = "## 第一回合结算\n\n" +
                "### 回合概述\n本回合玩家A抵达龙门，与龙门近卫局初步接触。\n\n" +
                "### 关键事件\n1. 玩家A在龙门商业区调查情报\n2. 遇到罗德岛干员\n\n" +
                "### 状态变化\n- 势力关系：龙门近卫局好感+5\n- 获得物品：龙门通行证\n\n" +
                "### 下回合风险\n- 整合运动可能袭击龙门\n\n" +
                "```json\n" +
                "{\"tool\":\"turn_settlement_save_last_response\"," +
                "\"args\":{\"inputSummary\":\"玩家A抵达龙门\"}}\n" +
                "```";
        fakeLlm.addResponse(round1);

        // Round 2: LLM 调用 branch_next_turn
        fakeLlm.addResponse("{\"tool\":\"branch_next_turn\"," +
                "\"args\":{\"worldTime\":\"泰拉纪年1096年冬\"}}");

        // Round 3: 最终自然语言回复
        fakeLlm.addResponse("{\"tool\":\"finish_action\",\"args\":{\"status\":\"success\","
                + "\"message\":\"第一回合结算已保存为 stl0001。已创建并进入第二回合 branch.b0002。\"}}");

        var result = agent.chatWithContextSession(
                "# Base\nbranch: branch.b0000-start\n",
                List.of(), "结算本回合并进入下一回合");

        assertTrue(result.success());
        assertTrue(result.toolCalls().size() >= 2,
                "Should execute at least settlement save + next turn tools, got: "
                        + result.toolCalls().size());

        // 验证工具调用顺序：先 save_last_response，再 branch_next_turn
        assertTrue(savedSettlement.get().length() > 0,
                "Turn settlement should have been saved from draft");
        assertTrue(savedSettlement.get().contains("第一回合结算"),
                "Saved settlement should contain the settlement text");
        assertTrue(savedSettlement.get().contains("玩家A抵达龙门"),
                "Saved settlement should contain actual content");
        assertTrue(nextTurnCalled.get(),
                "branch_next_turn should have been called");

        // 验证最终回复不含 raw JSON
        assertFalse(result.finalText().contains("{\"tool\""),
                "Final text should NOT contain raw tool JSON");
        assertFalse(result.finalText().contains("[工具结果]"),
                "Final text should NOT contain [工具结果]");
    }

    @Test
    @DisplayName("最终回复不含成功宣称警告（有工具支撑）")
    void finalTextHasNoSuccessClaimWarning() {
        // Round 1: settlement + save
        fakeLlm.addResponse("结算正文...\n\n```json\n" +
                "{\"tool\":\"turn_settlement_save_last_response\"," +
                "\"args\":{\"inputSummary\":\"test\"}}\n```");
        // Round 2: next turn
        fakeLlm.addResponse("{\"tool\":\"branch_next_turn\"," +
                "\"args\":{\"worldTime\":\"1096\"}}");
        // Round 3: final
        fakeLlm.addResponse("{\"tool\":\"finish_action\",\"args\":{\"status\":\"success\",\"message\":\"结算已保存为 stl0001。已进入 branch.b0002。\"}}");

        var result = agent.chatWithContextSession(
                "# Base\nbranch: branch.b0000-start\n",
                List.of(), "结算并进入下一回合");

        assertTrue(result.success());
        assertFalse(result.finalText().contains("[系统提示]"),
                "Should NOT have system warning when tools back claims");
        assertFalse(result.finalText().contains("未检测到对应的工具执行记录"),
                "Should NOT have unbacked tool claim warning");
    }

    // ===== Stubs =====

    static class StubSaveLastResponseTool implements AgentTool {
        private final AtomicReference<String> savedSettlement;
        private final java.util.function.Supplier<String> draftSupplier;

        StubSaveLastResponseTool(AtomicReference<String> savedSettlement,
                                 java.util.function.Supplier<String> draftSupplier) {
            this.savedSettlement = savedSettlement;
            this.draftSupplier = draftSupplier;
        }

        @Override public String name() { return "turn_settlement_save_last_response"; }
        @Override public String description() { return "Save last response as settlement."; }
        @Override
        public ToolResult execute(ToolCall call) {
            String draft = draftSupplier.get();
            if (draft == null || draft.isBlank()) {
                return ToolResult.fail(name(), "NO_LAST_ASSISTANT_DRAFT");
            }
            savedSettlement.set(draft);
            return ToolResult.ok(name(), List.of(
                    new ToolResult.Item("Settlement stl0001", "branch.b0001",
                            "status=OK\nsettlementId=stl0001\nsavedFrom=lastAssistantDraft\n" +
                                    "draftLength=" + draft.length() + "\n",
                            1.0)));
        }
    }

    static class StubBranchNextTurnTool implements AgentTool {
        private final AtomicReference<Boolean> called;

        StubBranchNextTurnTool(AtomicReference<Boolean> called) {
            this.called = called;
        }

        @Override public String name() { return "branch_next_turn"; }
        @Override public String description() { return "Create next turn and switch."; }
        @Override
        public ToolResult execute(ToolCall call) {
            called.set(true);
            return ToolResult.ok(name(), List.of(
                    new ToolResult.Item("Next turn: branch.b0002", "branch.b0002",
                            "status=OK\ncreatedBranchId=branch.b0002\nactiveBranchId=branch.b0002\n" +
                                    "switched=true\n", 1.0)));
        }
    }
}
