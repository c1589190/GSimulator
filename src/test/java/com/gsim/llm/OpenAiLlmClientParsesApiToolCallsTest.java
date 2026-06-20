package com.gsim.llm;

import com.gsim.agent.OrchestratorAgent;
import com.gsim.agent.tool.FinishActionTool;
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
 * 验证 FakeLlmClient 模拟的 API tool_calls 响应被正确解析并执行。
 */
@DisplayName("API tool_calls 响应解析并执行")
class OpenAiLlmClientParsesApiToolCallsTest {

    private FakeLlmClient fakeLlm;
    private ToolRegistry toolRegistry;
    private OrchestratorAgent agent;

    @BeforeEach
    void setUp() {
        fakeLlm = new FakeLlmClient();
        toolRegistry = new ToolRegistry();
        toolRegistry.register(new FinishActionTool());
        agent = new OrchestratorAgent(fakeLlm, toolRegistry, "test-model");
    }

    @Test
    @DisplayName("API tool_calls 中的 function.name 被正确解析为工具名")
    void apiToolCallsNameParsedCorrectly() {
        fakeLlm.addToolCallsResponse(List.of(
                new LlmToolCall("call_001", "finish_action",
                        Map.of("status", "success", "message", "任务完成。"))
        ));

        var result = agent.chatWithContextSession(
                "# Base\nbranch: branch.b0000-start\n",
                List.of(), "测试");

        assertTrue(result.success(), "API tool_calls should be executed: " + result.errorMessage());
        assertEquals(1, result.toolCalls().size());
        assertEquals("finish_action", result.toolCalls().get(0).tool());
    }

    @Test
    @DisplayName("function.arguments JSON 字符串被正确解析为 Map")
    void functionArgumentsParsedToMap() {
        fakeLlm.addToolCallsResponse(List.of(
                new LlmToolCall("call_002", "finish_action",
                        Map.of("status", "success", "message", "已完成。"))
        ));

        var result = agent.chatWithContextSession(
                "# Base\nbranch: branch.b0000-start\n",
                List.of(), "测试");

        assertTrue(result.success());
        assertTrue(result.finalText().contains("已完成"),
                "finalText should contain the message from tool_calls args");
    }

    @Test
    @DisplayName("多个 API tool_calls 按序执行")
    void multipleApiToolCallsExecuteInOrder() {
        toolRegistry.register(new EchoTool());
        // 第一轮: echo
        fakeLlm.addToolCallsResponse(List.of(
                new LlmToolCall("call_001", "echo", Map.of("message", "hello"))
        ));
        // 第二轮: finish_action（遵循 finish_action 必须单独调用的规则）
        fakeLlm.addResponse("{\"tool\":\"finish_action\",\"args\":{\"status\":\"success\","
                + "\"message\":\"完成。\"}}");

        var result = agent.chatWithContextSession(
                "# Base\nbranch: branch.b0000-start\n",
                List.of(), "测试");

        assertTrue(result.success(),
                "Should succeed: " + result.errorMessage());
        assertEquals(2, result.toolCalls().size());
        assertEquals("echo", result.toolCalls().get(0).tool());
        assertEquals("finish_action", result.toolCalls().get(1).tool());
    }

    @Test
    @DisplayName("空 tool_calls 列表不报错，走正常文本解析路径")
    void emptyApiToolCallsFallsBackToTextParsing() {
        // 无 API tool_calls 但有文本 JSON
        fakeLlm.addResponse("{\"tool\":\"finish_action\",\"args\":{\"status\":\"success\","
                + "\"message\":\"文本 fallback 完成。\"}}");

        var result = agent.chatWithContextSession(
                "# Base\nbranch: branch.b0000-start\n",
                List.of(), "测试");

        assertTrue(result.success());
        assertEquals(1, result.toolCalls().size());
        assertEquals("finish_action", result.toolCalls().get(0).tool());
    }

    // ===== Stub =====

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
