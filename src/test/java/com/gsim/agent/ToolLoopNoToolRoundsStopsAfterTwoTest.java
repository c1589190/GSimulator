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
 * 验证连续 3 轮无工具调用后 ToolLoop 提前中止。
 * 容忍度从 v1 的 2 轮提升到 3 轮。
 */
@DisplayName("ToolLoop 连续 3 轮无工具提前中止")
class ToolLoopNoToolRoundsStopsAfterTwoTest {

    private FakeLlmManager fakeLlm;
    private ToolRegistry toolRegistry;
    private OrchestratorAgent agent;

    @BeforeEach
    void setUp() {
        fakeLlm = new FakeLlmManager();
        toolRegistry = new ToolRegistry();
        toolRegistry.register(new com.gsim.agent.tool.FinishActionTool());
        agent = new OrchestratorAgent(fakeLlm, toolRegistry, "test-model");
    }

    @Test
    @DisplayName("连续 3 轮纯文本 → 第 3 轮触发提前中止")
    void stopsAfterTwoConsecutiveNoToolRounds() {
        // Round 1: 纯文本 → 显示给用户 → 提醒
        fakeLlm.addResponse("正在处理中...");
        // Round 2: 又是纯文本 → 显示给用户 → 第 2 次提醒
        fakeLlm.addResponse("还需要再看看...");
        // Round 3: 第 3 次纯文本 → 连续无工具轮数达到上限 → ABORT
        fakeLlm.addResponse("还是不确定...");

        var result = agent.chatWithContextSession(
                "# Base\nbranch: branch.b0000-start\n",
                List.of(), "执行一个任务");

        assertFalse(result.success(),
                "连续 3 轮纯文本无工具应触发中止");
        assertTrue(result.errorMessage() != null
                        && result.errorMessage().contains("no tool calls"),
                "错误消息应提到连续无工具轮数: " + result.errorMessage());
        assertEquals(0, result.toolCalls().size(),
                "不应有任何工具调用记录");
    }

    @Test
    @DisplayName("echo → 纯文本 → 纯文本 → finish_action 成功（工具调用重置计数器）")
    void toolCallResetsConsecutiveCounter() {
        // Round 1: echo 工具 → consecutiveNoToolRounds 重置为 0
        fakeLlm.addResponse("{\"tool\":\"echo\",\"args\":{\"message\":\"r1\"}}");
        // Round 2: 纯文本 → 提醒（consecutiveNoToolRounds=1）
        fakeLlm.addResponse("做了些事情...");
        // Round 3: finish_action → 正常结束
        fakeLlm.addResponse("{\"tool\":\"finish_action\",\"args\":{\"status\":\"success\","
                + "\"message\":\"所有操作已完成。\"}}");

        toolRegistry.register(new EchoTool());
        var result = agent.chatWithContextSession(
                "# Base\nbranch: branch.b0000-start\n",
                List.of(), "测试");

        assertTrue(result.success(),
                "echo + 一次纯文本提醒 + finish_action JSON = 应成功结束");
        assertEquals(2, result.toolCalls().size(),
                "1 echo + 1 finish_action = 2 tool calls");
        assertEquals("echo", result.toolCalls().get(0).tool());
        assertEquals("finish_action", result.toolCalls().get(1).tool());
    }

    @Test
    @DisplayName("finish_action 前有 1~2 次无工具提醒不中止（3 轮触发中止）")
    void singleNoToolRoundDoesNotAbort() {
        // Round 1: 纯文本（第 1 次）
        fakeLlm.addResponse("正在了解情况...");
        // Round 2: finish_action（收到提醒后按下）
        fakeLlm.addResponse("{\"tool\":\"finish_action\",\"args\":{\"status\":\"success\","
                + "\"message\":\"任务已了解，当前状态正常。\"}}");

        var result = agent.chatWithContextSession(
                "# Base\nbranch: branch.b0000-start\n",
                List.of(), "查看状态");

        assertTrue(result.success(),
                "Single no-tool round + finish_action should succeed");
        assertEquals(1, result.toolCalls().size());
        assertEquals("finish_action", result.toolCalls().get(0).tool());
    }

    // ===== Stub =====

    static class EchoTool implements AgentTool {
        @Override public String name() { return "echo"; }
        @Override public String description() { return "Echo."; }
        @Override
        public ToolResult execute(ToolCall call) {
            return ToolResult.ok(name(), List.of(
                    new ToolResult.Item("echo", "test", "ok", 1.0)));
        }
    }
}
