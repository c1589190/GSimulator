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

import static org.junit.jupiter.api.Assertions.*;

/**
 * 端到端测试：结算 → 下一回合 → finish_action。
 * 验证完整结算工作流以 finish_action 结束，工具调用有序，finalText 干净。
 */
@DisplayName("Agent 结算+下一回合工作流使用 finish_action")
class AgentSettlementNextTurnUsesFinishActionTest {

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
    @DisplayName("save → next_turn → finish_action → 3 个 tool calls 有序，finalText 干净")
    void settlementThenNextTurnThenFinishAction() {
        // Round 1: save
        fakeLlm.addResponse("{\"tool\":\"turn_settlement_save_last_response\","
                + "\"args\":{\"inputSummary\":\"第一回合结算\"}}");
        // Round 2: next_turn
        fakeLlm.addResponse("{\"tool\":\"branch_next_turn\","
                + "\"args\":{\"worldTime\":\"泰拉纪年1096年冬\"}}");
        // Round 3: finish_action
        fakeLlm.addResponse("{\"tool\":\"finish_action\",\"args\":{\"status\":\"success\","
                + "\"message\":\"第一回合结算已保存为 stl0001。已进入第二回合 branch.b0002。\"}}");

        var result = agent.chatWithContextSession(
                "# Base\nbranch: branch.b0000-start\n",
                List.of(), "结算本回合并进入下一回合");

        assertTrue(result.success());
        assertEquals(3, result.toolCalls().size());
        assertEquals("turn_settlement_save_last_response", result.toolCalls().get(0).tool());
        assertEquals("branch_next_turn", result.toolCalls().get(1).tool());
        assertEquals("finish_action", result.toolCalls().get(2).tool());
        assertTrue(result.finalText().contains("stl0001"),
                "finalText should contain settlement ID");
        assertTrue(result.finalText().contains("branch.b0002"),
                "finalText should contain branch ID");
        assertFalse(result.finalText().contains("\"tool\""),
                "finalText must not contain raw tool JSON");
        assertFalse(result.finalText().contains("[工具结果]"),
                "finalText must not contain tool result markers");
    }

    @Test
    @DisplayName("save + finish_action (不进入下一回合) → 2 个 tool calls")
    void settlementWithoutNextTurn() {
        fakeLlm.addResponse("{\"tool\":\"turn_settlement_save_last_response\","
                + "\"args\":{\"inputSummary\":\"第一回合摘要\"}}");
        fakeLlm.addResponse("{\"tool\":\"finish_action\",\"args\":{\"status\":\"success\","
                + "\"message\":\"第一回合结算已保存为 stl0001。当前仍在 branch.b0001。\"}}");

        var result = agent.chatWithContextSession(
                "# Base\nbranch: branch.b0001\n",
                List.of(), "仅结算");

        assertTrue(result.success());
        assertEquals(2, result.toolCalls().size());
        assertEquals("turn_settlement_save_last_response", result.toolCalls().get(0).tool());
        assertEquals("finish_action", result.toolCalls().get(1).tool());
    }

    // ===== Stubs =====

    static class StubSaveLastResponseTool implements AgentTool {
        @Override public String name() { return "turn_settlement_save_last_response"; }
        @Override public String description() { return "Save last response as settlement."; }
        @Override
        public ToolResult execute(ToolCall call) {
            return ToolResult.ok(name(), List.of(
                    new ToolResult.Item("Settlement stl0001", "branch.b0001",
                            "status=OK\nsettlementId=stl0001\n", 1.0)));
        }
    }

    static class StubBranchNextTurnTool implements AgentTool {
        @Override public String name() { return "branch_next_turn"; }
        @Override public String description() { return "Create next turn."; }
        @Override
        public ToolResult execute(ToolCall call) {
            return ToolResult.ok(name(), List.of(
                    new ToolResult.Item("Next turn: branch.b0002", "branch.b0002",
                            "createdBranchId=branch.b0002\nswitched=true\n", 1.0)));
        }
    }
}
