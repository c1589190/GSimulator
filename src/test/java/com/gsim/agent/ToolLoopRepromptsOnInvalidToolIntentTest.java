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
 * 验证非法工具意图（[调用 xxx]、{key=value} 等）触发一次回撤提示，
 * Agent 纠正后进入合法路径。
 */
@DisplayName("ToolLoop 非法工具意图一次回撤")
class ToolLoopRepromptsOnInvalidToolIntentTest {

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
    @DisplayName("一次口头'调用 xxx 工具' → reprompt → 合法 finish_action → 成功")
    void verbalToolIntentRepromptedThenRecovers() {
        // Round 1: 口头工具意图（非法）
        fakeLlm.addResponse("我先调用 player_action_list 工具查询数据...");
        // Round 2: 收到纠正 → 使用合法 JSON
        fakeLlm.addResponse("{\"tool\":\"finish_action\",\"args\":{\"status\":\"success\","
                + "\"message\":\"数据查询完成，已使用合法格式。\"}}");

        var result = agent.chatWithContextSession(
                "# Base\nbranch: branch.b0002\n",
                List.of(), "查询数据");

        assertTrue(result.success(),
                "Reprompt should allow recovery: " + result.errorMessage());
        assertEquals(1, result.toolCalls().size());
        assertEquals("finish_action", result.toolCalls().get(0).tool());
        assertFalse(result.finalText().contains("调用 player_action_list 工具"),
                "finalText should not contain the invalid round 1 content");
    }

    @Test
    @DisplayName("一次 [工具调用已执行] 伪结果 → reprompt → 合法 finish_action → 成功")
    void fakeToolExecutedMarkerRepromptedThenRecovers() {
        // Round 1: 伪工具结果标记
        fakeLlm.addResponse("[工具调用已执行] player_action_list 已返回结果。");
        // Round 2: 合法 finish_action
        fakeLlm.addResponse("{\"tool\":\"finish_action\",\"args\":{\"status\":\"success\","
                + "\"message\":\"已纠正格式。\"}}");

        var result = agent.chatWithContextSession(
                "# Base\nbranch: branch.b0000-start\n",
                List.of(), "查询");

        assertTrue(result.success(),
                "Should recover from fake tool-executed marker");
    }

    @Test
    @DisplayName("一次 [工具结果] 伪输出 → reprompt → 合法 finish_action → 成功")
    void fakeToolResultRepromptedThenRecovers() {
        // Round 1: [工具结果] 伪输出
        fakeLlm.addResponse("[工具结果] 查询完成。共找到 3 条记录。");
        // Round 2: 合法
        fakeLlm.addResponse("{\"tool\":\"finish_action\",\"args\":{\"status\":\"success\","
                + "\"message\":\"结果：共找到 3 条记录。\"}}");

        var result = agent.chatWithContextSession(
                "# Base\nbranch: branch.b0000-start\n",
                List.of(), "查询");

        assertTrue(result.success());
        assertTrue(result.finalText().contains("3 条记录"));
    }

    @Test
    @DisplayName("一次 {key=value} 非 JSON 格式 → reprompt → 合法 → 成功")
    void nonJsonFormatRepromptedThenRecovers() {
        fakeLlm.addResponse("{tool=echo, args={message=test}}");
        fakeLlm.addResponse("{\"tool\":\"finish_action\",\"args\":{\"status\":\"success\","
                + "\"message\":\"已改用合法 JSON。\"}}");

        var result = agent.chatWithContextSession(
                "# Base\nbranch: branch.b0000-start\n",
                List.of(), "测试");

        assertTrue(result.success(),
                "Should recover from non-JSON format reprompt");
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
                    new ToolResult.Item("echo", "test",
                            call.parameters().getOrDefault("message", "ok"), 1.0)));
        }
    }
}
