package com.gsim.agent;

import com.gsim.agent.tool.FinishActionTool;
import com.gsim.llm.FakeLlmClient;
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
 * 验证当 API tool_calls 和文本 fallback JSON 同时存在时，
 * ToolLoop 优先执行 API tool_calls（Layer 1 优先于 Layer 2）。
 */
@DisplayName("ToolLoop API tool_calls 优先于文本 fallback")
class ToolLoopPrefersApiToolCallsOverTextFallbackTest {

    private FakeLlmClient fakeLlm;
    private ToolRegistry toolRegistry;
    private OrchestratorAgent agent;

    @BeforeEach
    void setUp() {
        fakeLlm = new FakeLlmClient();
        toolRegistry = new ToolRegistry();
        toolRegistry.register(new FinishActionTool());
        toolRegistry.register(new EchoTool());
        agent = new OrchestratorAgent(fakeLlm, toolRegistry, "test-model");
    }

    @Test
    @DisplayName("同时有 API tool_calls(finish_action) 和文本 JSON(echo) → 只执行 API tool_calls")
    void apiToolCallsWinsWhenBothPresent() {
        // 响应同时包含 API tool_calls 和文本 JSON
        fakeLlm.addResponse(
                "{\"tool\":\"echo\",\"args\":{\"message\":\"这条文本 JSON 不应被执行\"}}",
                List.of(new LlmToolCall("call_001", "finish_action",
                        Map.of("status", "success", "message", "API tool_calls 优先完成。")))
        );

        var result = agent.chatWithContextSession(
                "# Base\nbranch: branch.b0000-start\n",
                List.of(), "测试多层优先级");

        assertTrue(result.success(),
                "Should succeed via API tool_calls: " + result.errorMessage());
        // 只应执行 API tool_calls 的 finish_action，不应执行文本中的 echo
        assertEquals(1, result.toolCalls().size(),
                "Should only execute API tool_calls, not text fallback");
        assertEquals("finish_action", result.toolCalls().get(0).tool(),
                "Executed tool should be finish_action from API, not echo from text");
        assertTrue(result.finalText().contains("API tool_calls 优先"),
                "finalText should come from API tool_calls finish_action message");
    }

    @Test
    @DisplayName("仅有文本 JSON（无 API tool_calls）→ 正常走 fallback 路径")
    void textFallbackWorksWhenNoApiToolCalls() {
        fakeLlm.addResponse("{\"tool\":\"finish_action\",\"args\":{\"status\":\"success\","
                + "\"message\":\"文本 fallback 完成。\"}}");

        var result = agent.chatWithContextSession(
                "# Base\nbranch: branch.b0000-start\n",
                List.of(), "测试 fallback 路径");

        assertTrue(result.success(),
                "Text fallback should work when no API tool_calls present");
        assertEquals(1, result.toolCalls().size());
        assertEquals("finish_action", result.toolCalls().get(0).tool());
        assertTrue(result.finalText().contains("文本 fallback 完成"));
    }

    @Test
    @DisplayName("API tool_calls(echo) + 文本(另一个 echo) → 只执行 API")
    void multipleApiToolCallsWinOverText() {
        // API 返回 echo，文本中还有另一个 echo — API 优先
        fakeLlm.addResponse(
                "{\"tool\":\"echo\",\"args\":{\"message\":\"text fallback echo\"}}",
                List.of(
                        new LlmToolCall("call_001", "echo",
                                Map.of("message", "API echo 完成。"))
                )
        );
        // 下一轮: finish_action
        fakeLlm.addResponse("{\"tool\":\"finish_action\",\"args\":{\"status\":\"success\","
                + "\"message\":\"API 优先完成。\"}}");

        var result = agent.chatWithContextSession(
                "# Base\nbranch: branch.b0000-start\n",
                List.of(), "测试多工具优先级");

        assertTrue(result.success(),
                "Should succeed: " + result.errorMessage());
        // 应该执行 2 个工具：echo (API) + finish_action (下一轮)
        assertEquals(2, result.toolCalls().size(),
                "Should execute echo from API and finish_action in next round");
        assertEquals("echo", result.toolCalls().get(0).tool());
        assertEquals("finish_action", result.toolCalls().get(1).tool());
    }

    // ===== Stub =====

    static class EchoTool implements AgentTool {
        @Override public String name() { return "echo"; }
        @Override public String description() { return "Echo test tool."; }
        @Override
        public ToolResult execute(ToolCall call) {
            return ToolResult.ok(name(), List.of(
                    new ToolResult.Item("echo", "test", call.parameters().getOrDefault("message", "ok"), 1.0)));
        }
    }
}
