package com.gsim.agent;

import com.gsim.agent.tool.FinishActionTool;
import com.gsim.llm.FakeLlmManager;
import com.gsim.llm.LlmToolCall;
import com.gsim.tool.AgentTool;
import com.gsim.tool.ToolCall;
import com.gsim.tool.ToolRegistry;
import com.gsim.tool.ToolResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 验证 finish_action 不被当作普通工具处理：
 * accepted 后不 append tool_result 继续 LLM loop。
 */
@DisplayName("finish_action 不被当作普通工具处理")
class FinishActionIsNotTreatedAsOrdinaryToolTest {

    private FakeLlmManager fakeLlm;
    private ToolRegistry toolRegistry;
    private OrchestratorAgent agent;

    @BeforeEach
    void setUp() {
        fakeLlm = new FakeLlmManager();
        toolRegistry = new ToolRegistry();
        toolRegistry.register(new FinishActionTool());
        agent = new OrchestratorAgent(fakeLlm, toolRegistry, "test-model");
    }

    @Test
    @DisplayName("finish_action 成功后不会继续 LLM loop")
    void finishActionSuccessDoesNotContinueLoop() {
        fakeLlm.addToolCallsResponse(List.of(
                new LlmToolCall("call_001", "finish_action",
                        Map.of("status", "success", "message", "直接完成，不继续。"))
        ));

        var result = agent.chatWithContextSession(
                "# Base\nbranch: branch.b0000-start\n",
                List.of(), "测试");

        assertTrue(result.success());
        assertEquals(1, result.toolCalls().size(),
                "Should have exactly 1 tool call (just finish_action)");

        // 只有 1 次 LLM request — 证明没有继续 loop
        assertEquals(1, fakeLlm.getRequestCount(),
                "finish_action accepted should NOT continue the loop");
    }

    @Test
    @DisplayName("echo 后不继续 — 必须 finish_action 才能结束")
    void afterEchoLoopMustContinue() {
        toolRegistry.register(new EchoTool());
        fakeLlm.addToolCallsResponse(List.of(
                new LlmToolCall("call_001", "echo", Map.of("message", "hello"))
        ));
        // 下一轮 finish_action
        fakeLlm.addToolCallsResponse(List.of(
                new LlmToolCall("call_002", "finish_action",
                        Map.of("status", "success", "message", "echo done."))
        ));

        var result = agent.chatWithContextSession(
                "# Base\nbranch: branch.b0000-start\n",
                List.of(), "测试");

        assertTrue(result.success());
        assertEquals(2, result.toolCalls().size());
        assertEquals("echo", result.toolCalls().get(0).tool());
        assertEquals("finish_action", result.toolCalls().get(1).tool());

        // echo 后必须继续，所以有 2 次请求；finish_action 后停止
        assertEquals(2, fakeLlm.getRequestCount(),
                "echo should NOT end the loop; finish_action should");
    }

    // ===== Stubs =====

    static class EchoTool implements AgentTool {
        @Override public String name() { return "echo"; }
        @Override public String description() { return "Echo test tool."; }
        @Override
        public ToolResult execute(ToolCall call) {
            return ToolResult.ok(name(), List.of(
                    new ToolResult.Item("echo", "test", "ok", 1.0)));
        }
    }
}
