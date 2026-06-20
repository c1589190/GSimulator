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
 * 验证 Agent 在没有 branch_next_turn 工具结果的情况下
 * 不得声称「已进入 b0002」或「已切换到下一回合」。
 * 适配 finish_action 架构：验证发生在 validateFinishActionClaims 中。
 */
@DisplayName("Agent 不得在无 branch_next_turn 结果时声称已进入分支 (finish_action)")
class AgentDoesNotClaimEnteredBranchWithoutBranchNextTurnResultTest {

    private FakeLlmManager fakeLlm;
    private ToolRegistry toolRegistry;
    private OrchestratorAgent agent;

    @BeforeEach
    void setUp() {
        fakeLlm = new FakeLlmManager();
        toolRegistry = new ToolRegistry();
        toolRegistry.register(new StubBranchNextTurnTool());
        toolRegistry.register(new com.gsim.agent.tool.FinishActionTool());
        agent = new OrchestratorAgent(fakeLlm, toolRegistry, "test-model");
    }

    @Test
    @DisplayName("无工具执行时 finish_action 声称「已进入 b0002」被拒绝")
    void claimEnteredB0002WithoutToolTriggersWarning() {
        // finish_action 声称已进入 b0002，没有先调用 branch_next_turn → 被拒绝
        fakeLlm.addResponse("{\"tool\":\"finish_action\",\"args\":{\"status\":\"success\","
                + "\"message\":\"已进入 b0002，这是第二回合的推演内容。当前分支 b0002 已激活，时间推进到下一阶段。\"}}");
        // 重试：诚实说明
        fakeLlm.addResponse("{\"tool\":\"finish_action\",\"args\":{\"status\":\"success\","
                + "\"message\":\"我尚未执行 branch_next_turn。请告知是否需要进入下一回合。\"}}");

        var result = agent.chatWithContextSession(
                "# Base\nbranch: branch.b0000-start\n",
                List.of(), "进入下一回合");

        assertTrue(result.success());
        // 两个 finish_action：第一个被拒绝，第二个被接受
        assertEquals(2, result.toolCalls().size(),
                "Two finish_action calls: first rejected, second accepted");
        // 最终回复不应包含被拒的 unbacked claim
        assertFalse(result.finalText().contains("已进入 b0002"),
                "Accepted message should NOT contain the rejected claim");
    }

    @Test
    @DisplayName("无工具执行时 finish_action 声称「已切换到 b0002」被拒绝")
    void claimSwitchedToB0002WithoutToolTriggersWarning() {
        fakeLlm.addResponse("{\"tool\":\"finish_action\",\"args\":{\"status\":\"success\","
                + "\"message\":\"操作完成。已切换到 branch.b0002，当前活跃分支已更新。推演继续...\"}}");
        fakeLlm.addResponse("{\"tool\":\"finish_action\",\"args\":{\"status\":\"success\","
                + "\"message\":\"我尚未切换分支。当前仍在 branch.b0000-start。\"}}");

        var result = agent.chatWithContextSession(
                "# Base\nbranch: branch.b0000-start\n",
                List.of(), "切换分支");

        assertTrue(result.success());
        assertEquals(2, result.toolCalls().size());
    }

    @Test
    @DisplayName("有 branch_next_turn 执行时 finish_action 声称「已进入 b0001」通过验证")
    void claimEnteredWithToolBackingDoesNotTriggerWarning() {
        // 第一轮：LLM 调用 branch_next_turn
        fakeLlm.addResponse("{\"tool\":\"branch_next_turn\"," +
                "\"args\":{\"worldTime\":\"泰拉纪年1096年冬\"}}");
        // 第二轮：LLM 调用 finish_action（有 tool 支撑）
        fakeLlm.addResponse("{\"tool\":\"finish_action\",\"args\":{\"status\":\"success\","
                + "\"message\":\"已创建并进入 branch.b0001。当前回合：1，世界时间：泰拉纪年1096年冬。推演开始...\"}}");

        var result = agent.chatWithContextSession(
                "# Base\nbranch: branch.b0000-start\n",
                List.of(), "开始第一回合");

        assertTrue(result.success());
        assertTrue(result.toolCalls().size() >= 2,
                "Should have at least 2 tool executions (branch_next_turn + finish_action)");
        assertFalse(result.finalText().contains("[系统提示]"),
                "Should NOT warn when tools back the claim");
    }

    @Test
    @DisplayName("无工具执行时 finish_action 声称「第一回合节点已创建」被拒绝")
    void claimFirstTurnCreatedWithoutToolTriggersWarning() {
        fakeLlm.addResponse("{\"tool\":\"finish_action\",\"args\":{\"status\":\"success\","
                + "\"message\":\"第一回合节点已创建，当前切换到 branch.b0001-first-turn。这是开始序言：在泰拉大陆的某个角落...\"}}");
        fakeLlm.addResponse("{\"tool\":\"finish_action\",\"args\":{\"status\":\"success\","
                + "\"message\":\"我尚未创建第一回合节点。请告知具体需求。\"}}");

        var result = agent.chatWithContextSession(
                "# Base\nbranch: branch.b0000-start\n",
                List.of(), "创建第一回合");

        assertTrue(result.success());
        assertEquals(2, result.toolCalls().size(),
                "Two finish_action: first rejected, second accepted");
    }

    // ===== Stub =====

    static class StubBranchNextTurnTool implements AgentTool {
        @Override
        public String name() { return "branch_next_turn"; }
        @Override
        public String description() { return "Create next turn node and switch."; }
        @Override
        public ToolResult execute(ToolCall call) {
            return ToolResult.ok(name(), List.of(
                    new ToolResult.Item("下一回合: branch.b0001", "branch.b0001",
                            "status=OK\ncreatedBranchId=branch.b0001\n" +
                                    "activeBranchId=branch.b0001\nswitched=true\n",
                            1.0)));
        }
    }
}
