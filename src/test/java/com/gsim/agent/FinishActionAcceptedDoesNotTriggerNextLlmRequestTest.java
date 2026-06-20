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
 * 验证 finish_action accepted 后没有发起下一轮 LLM request。
 */
@DisplayName("finish_action accepted 后不触发下一轮 LLM 请求")
class FinishActionAcceptedDoesNotTriggerNextLlmRequestTest {

    private FakeLlmManager fakeLlm;
    private ToolRegistry toolRegistry;
    private OrchestratorAgent agent;

    @BeforeEach
    void setUp() {
        fakeLlm = new FakeLlmManager();
        toolRegistry = new ToolRegistry();
        toolRegistry.register(new FinishActionTool());
        toolRegistry.register(new StubTool("player_action_list", "查询玩家行动。"));
        agent = new OrchestratorAgent(fakeLlm, toolRegistry, "test-model");
    }

    @Test
    @DisplayName("player_action_list → finish_action → 无第 3 轮")
    void twoRoundThenStopNoThirdRequest() {
        // R1: player_action_list via API
        fakeLlm.addToolCallsResponse(List.of(
                new LlmToolCall("call_001", "player_action_list",
                        Map.of("branchId", "branch.b0000-start"))
        ));
        // R2: finish_action via API
        fakeLlm.addToolCallsResponse(List.of(
                new LlmToolCall("call_002", "finish_action",
                        Map.of("status", "success", "message", "查询完毕，当前回合无行动记录。"))
        ));

        var result = agent.chatWithContextSession(
                "# Base\nbranch: branch.b0000-start\n",
                List.of(), "查看玩家行动");

        assertTrue(result.success(),
                "Should succeed: " + result.errorMessage());
        assertEquals(2, result.toolCalls().size());

        // 核心断言：只有 2 次 LLM request，没有第 3 轮
        assertEquals(2, fakeLlm.getRequestCount(),
                "Only 2 LLM requests should be sent — no round 3 after finish_action accepted");
    }

    @Test
    @DisplayName("单 finish_action → 仅 1 次请求")
    void singleFinishActionNoExtraRequest() {
        fakeLlm.addToolCallsResponse(List.of(
                new LlmToolCall("call_001", "finish_action",
                        Map.of("status", "success", "message", "直接完成。"))
        ));

        var result = agent.chatWithContextSession(
                "# Base\nbranch: branch.b0000-start\n",
                List.of(), "测试");

        assertTrue(result.success());
        assertEquals(1, fakeLlm.getRequestCount(),
                "Single finish_action should use exactly 1 LLM request");
    }

    @Test
    @DisplayName("回声 → finish_action → 仅 2 次请求")
    void echoThenFinishActionNoExtraRequest() {
        toolRegistry.register(new EchoTool());
        // R1: echo
        fakeLlm.addToolCallsResponse(List.of(
                new LlmToolCall("call_001", "echo", Map.of("message", "hello"))
        ));
        // R2: finish_action
        fakeLlm.addToolCallsResponse(List.of(
                new LlmToolCall("call_002", "finish_action",
                        Map.of("status", "success", "message", "echo 执行完毕。"))
        ));

        var result = agent.chatWithContextSession(
                "# Base\nbranch: branch.b0000-start\n",
                List.of(), "测试");

        assertTrue(result.success());
        assertEquals(2, fakeLlm.getRequestCount(),
                "echo + finish_action should use exactly 2 LLM requests");
    }

    // ===== Stubs =====

    static class StubTool implements AgentTool {
        private final String name;
        private final String desc;
        StubTool(String name, String desc) { this.name = name; this.desc = desc; }
        @Override public String name() { return name; }
        @Override public String description() { return desc; }
        @Override
        public ToolResult execute(ToolCall call) {
            return ToolResult.ok(name(), List.of(
                    new ToolResult.Item(name() + "_result", "ok", "ok", 1.0)));
        }
    }

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
