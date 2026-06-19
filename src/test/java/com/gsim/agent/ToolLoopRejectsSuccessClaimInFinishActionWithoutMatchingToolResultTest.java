package com.gsim.agent;

import com.gsim.llm.FakeLlmClient;
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
 * 验证 finish_action 声称的"已保存/已创建/已切换/已写入/已记录"
 * 必须有对应的工具执行记录支撑，否则被拒绝。
 */
@DisplayName("ToolLoop 拒绝无工具支撑的 finish_action 成功宣称")
class ToolLoopRejectsSuccessClaimInFinishActionWithoutMatchingToolResultTest {

    private FakeLlmClient fakeLlm;
    private ToolRegistry toolRegistry;
    private OrchestratorAgent agent;

    @BeforeEach
    void setUp() {
        fakeLlm = new FakeLlmClient();
        toolRegistry = new ToolRegistry();
        toolRegistry.register(new StubSaveTool());
        toolRegistry.register(new StubBranchNextTurnTool());
        toolRegistry.register(new StubKnowledgeUpsertTool());
        toolRegistry.register(new StubPlayerActionAppendTool());
        toolRegistry.register(new com.gsim.agent.tool.FinishActionTool());
        agent = new OrchestratorAgent(fakeLlm, toolRegistry, "test-model");
    }

    @Test
    @DisplayName("'已保存' 无 turn_settlement_save 工具 → 被拒绝 → 重试")
    void savedClaimWithoutSaveToolIsRejected() {
        fakeLlm.addResponse("{\"tool\":\"finish_action\",\"args\":{\"status\":\"success\","
                + "\"message\":\"结算已保存为 stl0001。\"}}");
        fakeLlm.addResponse("{\"tool\":\"finish_action\",\"args\":{\"status\":\"success\","
                + "\"message\":\"我尚未保存结算。请告知要保存的内容。\"}}");

        var result = agent.chatWithContextSession(
                "# Base\nbranch: branch.b0000-start\n",
                List.of(), "保存");

        assertTrue(result.success());
        assertEquals(2, result.toolCalls().size());
        assertFalse(result.finalText().contains("已保存"),
                "Accepted message should not contain unbacked claim");
    }

    @Test
    @DisplayName("'已进入' 无 branch_next_turn 工具 → 被拒绝 → 重试")
    void enteredClaimWithoutBranchToolIsRejected() {
        fakeLlm.addResponse("{\"tool\":\"finish_action\",\"args\":{\"status\":\"success\","
                + "\"message\":\"已进入 branch.b0002。第二回合开始。\"}}");
        fakeLlm.addResponse("{\"tool\":\"finish_action\",\"args\":{\"status\":\"success\","
                + "\"message\":\"我尚未执行分支切换。当前仍在 branch.b0000-start。\"}}");

        var result = agent.chatWithContextSession(
                "# Base\nbranch: branch.b0000-start\n",
                List.of(), "进入下一回合");

        assertTrue(result.success());
        assertEquals(2, result.toolCalls().size());
        assertFalse(result.finalText().contains("已进入"),
                "Accepted message should not contain unbacked claim");
    }

    @Test
    @DisplayName("'已写入' 无 knowledge_upsert → 被拒绝")
    void writtenClaimWithoutUpsertIsRejected() {
        fakeLlm.addResponse("{\"tool\":\"finish_action\",\"args\":{\"status\":\"success\","
                + "\"message\":\"龙门资料已写入知识库。\"}}");
        fakeLlm.addResponse("{\"tool\":\"finish_action\",\"args\":{\"status\":\"success\","
                + "\"message\":\"我尚未执行写入操作。请告知要写入什么内容。\"}}");

        var result = agent.chatWithContextSession(
                "# Base\nbranch: branch.b0000-start\n",
                List.of(), "记录龙门");

        assertTrue(result.success());
        assertEquals(2, result.toolCalls().size());
        assertFalse(result.finalText().contains("已写入"),
                "Accepted message should not contain unbacked claim");
    }

    @Test
    @DisplayName("'已记录' 无 player_action_append → 被拒绝")
    void recordedClaimWithoutAppendIsRejected() {
        fakeLlm.addResponse("{\"tool\":\"finish_action\",\"args\":{\"status\":\"success\","
                + "\"message\":\"玩家行动已记录。\"}}");
        fakeLlm.addResponse("{\"tool\":\"finish_action\",\"args\":{\"status\":\"success\","
                + "\"message\":\"我尚未记录玩家行动。请告知具体行动内容。\"}}");

        var result = agent.chatWithContextSession(
                "# Base\nbranch: branch.b0000-start\n",
                List.of(), "记录行动");

        assertTrue(result.success());
        assertEquals(2, result.toolCalls().size());
        assertFalse(result.finalText().contains("已记录"),
                "Accepted message should not contain unbacked claim");
    }

    @Test
    @DisplayName("'已保存' 有 turn_settlement_save 工具支撑 → 通过验证")
    void savedClaimWithToolBackingPasses() {
        fakeLlm.addResponse("{\"tool\":\"turn_settlement_save_last_response\","
                + "\"args\":{\"inputSummary\":\"test\"}}");
        fakeLlm.addResponse("{\"tool\":\"finish_action\",\"args\":{\"status\":\"success\","
                + "\"message\":\"结算已保存为 stl0001，状态正常。\"}}");

        var result = agent.chatWithContextSession(
                "# Base\nbranch: branch.b0000-start\n",
                List.of(), "保存结算");

        assertTrue(result.success());
        assertEquals(2, result.toolCalls().size());
        assertTrue(result.finalText().contains("stl0001"),
                "Claim is backed by tool → message should be returned as-is");
    }

    // ===== Stubs =====

    static class StubSaveTool implements AgentTool {
        @Override public String name() { return "turn_settlement_save_last_response"; }
        @Override public String description() { return "Save last response."; }
        @Override
        public ToolResult execute(ToolCall call) {
            return ToolResult.ok(name(), List.of(
                    new ToolResult.Item("stl0001", "b1", "status=OK", 1.0)));
        }
    }

    static class StubBranchNextTurnTool implements AgentTool {
        @Override public String name() { return "branch_next_turn"; }
        @Override public String description() { return "Create next turn."; }
        @Override
        public ToolResult execute(ToolCall call) {
            return ToolResult.ok(name(), List.of(
                    new ToolResult.Item("b2", "b2", "switched=true", 1.0)));
        }
    }

    static class StubKnowledgeUpsertTool implements AgentTool {
        @Override public String name() { return "knowledge_upsert"; }
        @Override public String description() { return "写入知识库。"; }
        @Override
        public ToolResult execute(ToolCall call) {
            return ToolResult.ok(name(), List.of(
                    new ToolResult.Item("k1", "k1", "upserted", 1.0)));
        }
    }

    static class StubPlayerActionAppendTool implements AgentTool {
        @Override public String name() { return "player_action_append"; }
        @Override public String description() { return "记录玩家行动。"; }
        @Override
        public ToolResult execute(ToolCall call) {
            return ToolResult.ok(name(), List.of(
                    new ToolResult.Item("a1", "a1", "appended", 1.0)));
        }
    }
}
