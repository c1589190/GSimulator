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
 * 验证达到 maxToolRounds 仍未调用 finish_action 时 ToolLoop 返回错误。
 */
@DisplayName("ToolLoop 达到最大轮数无 finish_action 返回错误")
class ToolLoopMaxRoundsWithoutFinishActionReturnsErrorTest {

    private FakeLlmClient fakeLlm;
    private ToolRegistry toolRegistry;
    private OrchestratorAgent agent;

    @BeforeEach
    void setUp() {
        fakeLlm = new FakeLlmClient();
        toolRegistry = new ToolRegistry();
        toolRegistry.register(new EchoTool());
        // 故意不注册 FinishActionTool，让 finish_action 调用失败
        // （或注册它但让 LLM 不调用它）
        toolRegistry.register(new com.gsim.agent.tool.FinishActionTool());
        agent = new OrchestratorAgent(fakeLlm, toolRegistry, "test-model");
    }

    @Test
    @DisplayName("maxToolRounds=5 时纯自然语言无 finish_action → ToolLoop 返回错误")
    void fiveRoundsOfPlainTextReturnsError() {
        agent.setMaxToolRounds(5);
        // 5 轮全部返回纯自然语言（无 tool call），但会在连续无工具时提前中止
        fakeLlm.addResponse("正在处理...");
        fakeLlm.addResponse("还需要更多信息...");
        fakeLlm.addResponse("让我再检查一下...");
        fakeLlm.addResponse("似乎还没完成...");
        fakeLlm.addResponse("最后一次尝试...");

        var result = agent.chatWithContextSession(
                "# Base\nbranch: branch.b0000-start\n",
                List.of(), "执行复杂任务");

        assertFalse(result.success(),
                "ToolLoop should fail when no finish_action within maxToolRounds");
        assertNotNull(result.errorMessage());
        assertTrue(result.errorMessage().contains("finish_action")
                        || result.errorMessage().contains("rounds"),
                "Error message should mention finish_action or max rounds: " + result.errorMessage());
    }

    @Test
    @DisplayName("maxToolRounds=5 时工具调用但无 finish_action → ToolLoop 返回错误")
    void fiveRoundsOfToolCallsWithoutFinishActionReturnsError() {
        agent.setMaxToolRounds(5);
        // 每轮都调用 echo，但都不调用 finish_action
        for (int i = 0; i < 5; i++) {
            fakeLlm.addResponse("{\"tool\":\"echo\",\"args\":{\"message\":\"round" + i + "\"}}");
        }

        var result = agent.chatWithContextSession(
                "# Base\nbranch: branch.b0000-start\n",
                List.of(), "测试");

        assertFalse(result.success(),
                "ToolLoop should fail when no finish_action even after 5 tool rounds");
        assertTrue(result.toolCalls().size() >= 5,
                "All tool calls should be recorded even though final result is error");
    }

    @Test
    @DisplayName("第 4 轮 finish_action → 正常结束（不触发 maxToolRounds）")
    void finishActionOnRound4Succeeds() {
        // 前三轮：echo 工具调用
        fakeLlm.addResponse("{\"tool\":\"echo\",\"args\":{\"message\":\"r1\"}}");
        fakeLlm.addResponse("{\"tool\":\"echo\",\"args\":{\"message\":\"r2\"}}");
        fakeLlm.addResponse("{\"tool\":\"echo\",\"args\":{\"message\":\"r3\"}}");
        // 第四轮：finish_action
        fakeLlm.addResponse("{\"tool\":\"finish_action\",\"args\":{\"status\":\"success\","
                + "\"message\":\"操作完成。\"}}");

        var result = agent.chatWithContextSession(
                "# Base\nbranch: branch.b0000-start\n",
                List.of(), "测试");

        assertTrue(result.success(),
                "ToolLoop should succeed: finish_action called on round 4");
        assertEquals(4, result.toolCalls().size(),
                "3 echo + 1 finish_action = 4 tool calls");
    }

    // ===== Stub =====

    static class EchoTool implements AgentTool {
        @Override public String name() { return "echo"; }
        @Override public String description() { return "Echo tool."; }
        @Override
        public ToolResult execute(ToolCall call) {
            return ToolResult.ok(name(), List.of(
                    new ToolResult.Item("echo", "test", "ok", 1.0)));
        }
    }
}
