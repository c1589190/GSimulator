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
 * 验证连续 2 轮无工具调用后 ToolLoop 提前中止，
 * 不再烧满 maxToolRounds（可配置，默认 8）。
 */
@DisplayName("ToolLoop 连续 2 轮无工具提前中止")
class ToolLoopNoToolRoundsStopsAfterTwoTest {

    private FakeLlmClient fakeLlm;
    private ToolRegistry toolRegistry;
    private OrchestratorAgent agent;

    @BeforeEach
    void setUp() {
        fakeLlm = new FakeLlmClient();
        toolRegistry = new ToolRegistry();
        toolRegistry.register(new com.gsim.agent.tool.FinishActionTool());
        agent = new OrchestratorAgent(fakeLlm, toolRegistry, "test-model");
    }

    @Test
    @DisplayName("连续 2 轮纯自然语言 → R1 触发 forced finish_action → R2 auto-wrap 成功")
    void stopsAfterTwoConsecutiveNoToolRounds() {
        // Round 1: 纯自然语言 → 触发 forcedFinishAction
        fakeLlm.addResponse("正在处理中...");
        // Round 2: 又是纯自然语言 → 被 auto-wrap 为 finish_action("还需要再看看...")
        fakeLlm.addResponse("还需要再看看...");

        var result = agent.chatWithContextSession(
                "# Base\nbranch: branch.b0000-start\n",
                List.of(), "执行一个任务");

        assertTrue(result.success(),
                "R2 纯文本应被 auto-wrap 为 finish_action 并成功");
        assertEquals(1, result.toolCalls().size(),
                "应记录 1 个 auto-wrapped finish_action");
        assertEquals("finish_action", result.toolCalls().get(0).tool());
        assertEquals("还需要再看看...", result.finalText());
    }

    @Test
    @DisplayName("echo → 纯文本(forcedFinishAction) → finish_action 成功结束")
    void toolCallResetsConsecutiveCounter() {
        // Round 1: echo 工具 → consecutiveNoToolRounds 重置为 0
        fakeLlm.addResponse("{\"tool\":\"echo\",\"args\":{\"message\":\"r1\"}}");
        // Round 2: 纯自然语言 → 触发 forcedFinishAction
        fakeLlm.addResponse("做了些事情...");
        // Round 3: forced finish_action 阶段，返回 finish_action 结束
        fakeLlm.addResponse("{\"tool\":\"finish_action\",\"args\":{\"status\":\"success\","
                + "\"message\":\"所有操作已完成。\"}}");

        toolRegistry.register(new EchoTool());
        var result = agent.chatWithContextSession(
                "# Base\nbranch: branch.b0000-start\n",
                List.of(), "测试");

        assertTrue(result.success(),
                "echo + forced finish_action + finish_action JSON = 应成功");
        assertEquals(2, result.toolCalls().size(),
                "1 echo + 1 finish_action = 2 tool calls");
        assertEquals("echo", result.toolCalls().get(0).tool());
        assertEquals("finish_action", result.toolCalls().get(1).tool());
    }

    @Test
    @DisplayName("finish_action 前有一次无工具提醒不中止（仅1次无工具不触发中止）")
    void singleNoToolRoundDoesNotAbort() {
        // Round 1: 纯自然语言（第 1 次）
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
