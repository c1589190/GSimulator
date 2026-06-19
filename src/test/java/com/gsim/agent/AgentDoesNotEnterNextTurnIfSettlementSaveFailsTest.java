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
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 验证当 turn_settlement_save_last_response 失败时，
 * Agent 不得进入下一回合，不得声称已保存/已进入。
 * 适配 finish_action 架构。
 */
@DisplayName("Agent 不得在结算保存失败时进入下一回合 (finish_action)")
class AgentDoesNotEnterNextTurnIfSettlementSaveFailsTest {

    private FakeLlmClient fakeLlm;
    private ToolRegistry toolRegistry;
    private OrchestratorAgent agent;
    private AtomicBoolean nextTurnCalled;

    @BeforeEach
    void setUp() {
        fakeLlm = new FakeLlmClient();
        toolRegistry = new ToolRegistry();
        nextTurnCalled = new AtomicBoolean(false);

        // 注册会失败的 save_last_response 工具
        toolRegistry.register(new FailingSaveLastResponseTool());
        // 注册 branch_next_turn 工具
        toolRegistry.register(new StubBranchNextTurnTool(nextTurnCalled));
        toolRegistry.register(new com.gsim.agent.tool.FinishActionTool());

        agent = new OrchestratorAgent(fakeLlm, toolRegistry, "test-model");
    }

    @Test
    @DisplayName("save_last_response 失败时 branch_next_turn 未被调用")
    void nextTurnNotCalledWhenSaveFails() {
        // Round 1: 结算正文 + save_last_response tool call（会失败）
        fakeLlm.addResponse("## 第一回合结算\n\n玩家抵达龙门...\n\n" +
                "```json\n" +
                "{\"tool\":\"turn_settlement_save_last_response\"," +
                "\"args\":{\"inputSummary\":\"test\"}}\n" +
                "```");
        // Round 2: LLM 看到失败后诚实报告
        fakeLlm.addResponse("{\"tool\":\"finish_action\",\"args\":{\"status\":\"failed\","
                + "\"message\":\"结算保存失败，无法进入下一回合。请检查系统状态。\"}}");

        var result = agent.chatWithContextSession(
                "# Base\nbranch: branch.b0000-start\n",
                List.of(), "结算并进入下一回合");

        assertTrue(result.success());
        // save_last_response 被调用了（即使失败）
        assertTrue(result.toolCalls().size() >= 2,
                "save_last_response should have been attempted + finish_action");
        // branch_next_turn 不应该被调用
        assertFalse(nextTurnCalled.get(),
                "branch_next_turn should NOT be called when save fails");
        // finalText 不应声称成功
        assertFalse(result.finalText().contains("已进入下一回合"),
                "Should NOT claim entered next turn");
    }

    @Test
    @DisplayName("save_last_response 失败 + finish_action 被调用 → toolCalls 非空")
    void failingToolStillAppearsInToolCalls() {
        // Round 1: settlement + save_last_response（会失败）
        fakeLlm.addResponse("结算正文...\n\n```json\n" +
                "{\"tool\":\"turn_settlement_save_last_response\"," +
                "\"args\":{\"inputSummary\":\"test\"}}\n```");
        // Round 2: LLM 声称成功但 save 工具失败了（honest: reports the attempt）
        // turn_settlement_save_last_response 已执行但 FAILED → NOT in successTools
        // → "已保存" 不会被 backing 验证通过
        // 改为诚实报告失败
        fakeLlm.addResponse("{\"tool\":\"finish_action\",\"args\":{\"status\":\"failed\","
                + "\"message\":\"结算保存失败：NO_LAST_ASSISTANT_DRAFT。\"}}");

        var result = agent.chatWithContextSession(
                "# Base\nbranch: branch.b0000-start\n",
                List.of(), "结算并进入下一回合");

        assertTrue(result.success());
        // save_last_response 工具被执行了（即使失败），所以 toolCalls 非空
        assertTrue(result.toolCalls().size() >= 2,
                "At least 2 tool calls (failed save + finish_action)");
        // branch_next_turn 未被调用
        assertFalse(nextTurnCalled.get());
    }

    @Test
    @DisplayName("save_last_response 失败后诚实报告 → 不触发 guard 警告")
    void honestFailureDoesNotTriggerWarning() {
        // Round 1: settlement + save_last_response（会失败）
        fakeLlm.addResponse("推演内容摘要...\n\n```json\n" +
                "{\"tool\":\"turn_settlement_save_last_response\"," +
                "\"args\":{\"inputSummary\":\"test\"}}\n```");
        // Round 2: LLM 诚实报告失败（finish_action status=failed）
        fakeLlm.addResponse("{\"tool\":\"finish_action\",\"args\":{\"status\":\"failed\","
                + "\"message\":\"结算保存失败：NO_LAST_ASSISTANT_DRAFT。请先确保先生成了结算内容再保存。\"}}");

        var result = agent.chatWithContextSession(
                "# Base\nbranch: branch.b0000-start\n",
                List.of(), "结算");

        assertTrue(result.success());
        assertFalse(result.finalText().contains("[系统提示]"),
                "Honest failure report should not trigger guard");
        assertFalse(nextTurnCalled.get(),
                "branch_next_turn should not be called");
    }

    // ===== Stubs =====

    static class FailingSaveLastResponseTool implements AgentTool {
        @Override public String name() { return "turn_settlement_save_last_response"; }
        @Override public String description() { return "Save last response as settlement."; }
        @Override
        public ToolResult execute(ToolCall call) {
            return ToolResult.fail(name(), "NO_LAST_ASSISTANT_DRAFT: 没有缓存的 assistant 回复。");
        }
    }

    static class StubBranchNextTurnTool implements AgentTool {
        private final AtomicBoolean called;
        StubBranchNextTurnTool(AtomicBoolean called) { this.called = called; }

        @Override public String name() { return "branch_next_turn"; }
        @Override public String description() { return "Create next turn and switch."; }
        @Override
        public ToolResult execute(ToolCall call) {
            called.set(true);
            return ToolResult.ok(name(), List.of(
                    new ToolResult.Item("Next turn", "branch.b0002",
                            "status=OK\nswitched=true\n", 1.0)));
        }
    }
}
