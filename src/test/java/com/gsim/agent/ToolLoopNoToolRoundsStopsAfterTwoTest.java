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
    @DisplayName("连续 2 轮纯自然语言无 tool → 第 2 轮后立即返回错误，不烧满 5 轮")
    void stopsAfterTwoConsecutiveNoToolRounds() {
        // Round 1: 纯自然语言
        fakeLlm.addResponse("正在处理中...");
        // Round 2: 又是纯自然语言
        fakeLlm.addResponse("还需要再看看...");

        var result = agent.chatWithContextSession(
                "# Base\nbranch: branch.b0000-start\n",
                List.of(), "执行一个任务");

        assertFalse(result.success(),
                "ToolLoop should fail after 2 consecutive no-tool rounds");
        assertTrue(result.errorMessage() != null && result.errorMessage().contains("consecutive"),
                "Error message should mention consecutive rounds: " + result.errorMessage());
        assertEquals(0, result.toolCalls().size(),
                "No tools should have been called before abort");
        assertTrue(result.finalText() != null && result.finalText().contains("连续"),
                "finalText should contain consecutive abort message");
    }

    @Test
    @DisplayName("工具调用后连续计数器重置 → 随后连续 2 轮无工具仍中止")
    void toolCallResetsConsecutiveCounter() {
        // Round 1: echo 工具 → consecutiveNoToolRounds 重置为 0
        fakeLlm.addResponse("{\"tool\":\"echo\",\"args\":{\"message\":\"r1\"}}");
        // Round 2: 纯自然语言（consecutiveNoTool=1）
        fakeLlm.addResponse("做了些事情...");
        // Round 3: 仅 2 个预设响应，FakeLlm 返回 defaultResponse "{}"
        // "{}" 无 tool → consecutiveNoTool=2 → 中止

        toolRegistry.register(new EchoTool());
        var result = agent.chatWithContextSession(
                "# Base\nbranch: branch.b0000-start\n",
                List.of(), "测试");

        assertFalse(result.success(),
                "Should abort after 2 consecutive no-tool rounds (rounds 2-3)");
        assertTrue(result.errorMessage() != null && result.errorMessage().contains("consecutive"),
                "Error should mention consecutive rounds");
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
