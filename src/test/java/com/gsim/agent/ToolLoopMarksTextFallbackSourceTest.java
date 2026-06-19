package com.gsim.agent;

import com.gsim.agent.tool.FinishActionTool;
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
 * 验证当工具调用来自文本 fallback 路径时被正确标记和执行。
 * 文本 fallback = 非 API tool_calls 的工具调用，即 fenced JSON 或裸 JSON。
 */
@DisplayName("ToolLoop 标记文本 fallback 调用源")
class ToolLoopMarksTextFallbackSourceTest {

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
    @DisplayName("文本 JSON fallback → 正确执行工具（验证文本路径可行）")
    void textFallbackJsonExecutedCorrectly() {
        fakeLlm.addResponse("{\"tool\":\"echo\",\"args\":{\"message\":\"hello from fallback\"}}");
        fakeLlm.addResponse("{\"tool\":\"finish_action\",\"args\":{\"status\":\"success\","
                + "\"message\":\"文本 fallback 路径执行成功。\"}}");

        var result = agent.chatWithContextSession(
                "# Base\nbranch: branch.b0000-start\n",
                List.of(), "测试文本 fallback 路径");

        assertTrue(result.success());
        assertEquals(2, result.toolCalls().size());
        assertEquals("echo", result.toolCalls().get(0).tool());
        assertEquals("finish_action", result.toolCalls().get(1).tool());
        assertTrue(result.finalText().contains("文本 fallback 路径执行成功"));
    }

    @Test
    @DisplayName("fenced ```json 块 fallback → 正确执行")
    void fencedJsonBlockExecutedCorrectly() {
        fakeLlm.addResponse("```json\n"
                + "{\"tool\":\"finish_action\",\"args\":{\"status\":\"success\","
                + "\"message\":\"fenced JSON 完成。\"}}\n"
                + "```");

        var result = agent.chatWithContextSession(
                "# Base\nbranch: branch.b0000-start\n",
                List.of(), "测试 fenced JSON");

        assertTrue(result.success());
        assertEquals(1, result.toolCalls().size());
        assertEquals("finish_action", result.toolCalls().get(0).tool());
        assertTrue(result.finalText().contains("fenced JSON 完成"));
    }

    @Test
    @DisplayName("文本 JSON（无 API tool_calls）→ toolCalls 列表正确填充")
    void toolCallsFromTextFallbackAreTracked() {
        fakeLlm.addResponse("{\"tool\":\"echo\",\"args\":{\"message\":\"r1\"}}");
        fakeLlm.addResponse("{\"tool\":\"echo\",\"args\":{\"message\":\"r2\"}}");
        fakeLlm.addResponse("{\"tool\":\"finish_action\",\"args\":{\"status\":\"success\","
                + "\"message\":\"完成。\"}}");

        var result = agent.chatWithContextSession(
                "# Base\nbranch: branch.b0000-start\n",
                List.of(), "测试 toolCalls 记录");

        assertTrue(result.success());
        assertEquals(3, result.toolCalls().size(),
                "All text fallback tool calls should be tracked");
        assertEquals("echo", result.toolCalls().get(0).tool());
        assertEquals("echo", result.toolCalls().get(1).tool());
        assertEquals("finish_action", result.toolCalls().get(2).tool());
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
