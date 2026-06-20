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
 * 验证 finish_action 与其他工具同轮混用时被拒绝。
 */
@DisplayName("finish_action 同轮混用被拒绝")
class FinishActionWithOtherToolsInSameRoundIsRejectedTest {

    private FakeLlmManager fakeLlm;
    private ToolRegistry toolRegistry;
    private CapturingProgressSink sink;
    private OrchestratorAgent agent;

    @BeforeEach
    void setUp() {
        fakeLlm = new FakeLlmManager();
        toolRegistry = new ToolRegistry();
        toolRegistry.register(new FinishActionTool());
        toolRegistry.register(new StubTool("player_action_list", "查询玩家行动。"));
        sink = new CapturingProgressSink();
        agent = new OrchestratorAgent(fakeLlm, toolRegistry, "test-model", sink);
    }

    @Test
    @DisplayName("[player_action_list, finish_action] 同轮 → 全部拒绝，不执行任何工具")
    void mixedToolsInSameRoundRejectedWithZeroExecution() {
        // R1: API tool_calls 同时包含 player_action_list 和 finish_action
        fakeLlm.addToolCallsResponse(List.of(
                new LlmToolCall("call_001", "player_action_list",
                        Map.of("branchId", "branch.b0000-start")),
                new LlmToolCall("call_002", "finish_action",
                        Map.of("status", "success", "message", "查询完毕。"))
        ));
        // R2: 重写（仅 finish_action）
        fakeLlm.addToolCallsResponse(List.of(
                new LlmToolCall("call_003", "finish_action",
                        Map.of("status", "success", "message", "先查再报：当前回合暂无行动记录。"))
        ));

        var result = agent.chatWithContextSession(
                "# Base\nbranch: branch.b0000-start\n",
                List.of(), "查看玩家行动");

        assertTrue(result.success(),
                "Should recover after mixed-tool rejection: " + result.errorMessage());

        // R1 没有任何工具被执行
        List<AgentProgressEvent> rejectedEvents = sink.events.stream()
                .filter(e -> AgentProgressEvent.FINISH_ACTION_REJECTED.equals(e.phase()))
                .toList();
        assertFalse(rejectedEvents.isEmpty(),
                "Should have FINISH_ACTION_REJECTED event for mixed tools");
        assertTrue(rejectedEvents.get(0).detail().contains("FINISH_ACTION_WITH_OTHER_TOOLS"),
                "Reject reason should be FINISH_ACTION_WITH_OTHER_TOOLS: " + rejectedEvents.get(0).detail());

        // R2 只执行了 finish_action（player_action_list 未执行）
        assertEquals(1, result.toolCalls().size(),
                "Only the valid finish_action from R2 should be executed");
        assertEquals("finish_action", result.toolCalls().get(0).tool());
    }

    @Test
    @DisplayName("[echo, finish_action] API 同轮 → 混用拒绝")
    void apiMixedToolsRejected() {
        toolRegistry.register(new EchoTool());
        // R1: echo + finish_action 同轮混用
        fakeLlm.addToolCallsResponse(List.of(
                new LlmToolCall("call_001", "echo", Map.of("message", "hello")),
                new LlmToolCall("call_002", "finish_action",
                        Map.of("status", "success", "message", "done."))
        ));
        // R2: 仅 finish_action
        fakeLlm.addToolCallsResponse(List.of(
                new LlmToolCall("call_003", "finish_action",
                        Map.of("status", "success", "message", "done correctly."))
        ));

        var result = agent.chatWithContextSession(
                "# Base\nbranch: branch.b0000-start\n",
                List.of(), "测试");

        assertTrue(result.success(),
                "Should recover: " + result.errorMessage());
        // R1 工具全部未执行，只有 R2 的 finish_action
        assertEquals(1, result.toolCalls().size());
        assertEquals("finish_action", result.toolCalls().get(0).tool());

        boolean hasMixedReject = sink.events.stream()
                .anyMatch(e -> AgentProgressEvent.FINISH_ACTION_REJECTED.equals(e.phase())
                        && e.detail().contains("FINISH_ACTION_WITH_OTHER_TOOLS"));
        assertTrue(hasMixedReject, "Should emit FINISH_ACTION_WITH_OTHER_TOOLS rejection event");
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
                    new ToolResult.Item(name() + "_result", "ok", "stub result", 1.0)));
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

    static class CapturingProgressSink implements AgentProgressSink {
        final List<AgentProgressEvent> events = new java.util.ArrayList<>();
        @Override
        public void onProgress(AgentProgressEvent event) {
            events.add(event);
        }
    }
}
