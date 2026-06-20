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
 * 验证 finish_action 与其他工具同轮混用规则：
 * finish_action 必须出现在工具调用的最末尾，否则被拒绝。
 */
@DisplayName("finish_action 必须出现在末尾")
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
    @DisplayName("[player_action_list, finish_action] 同轮 → finish_action 在末尾 → 全部执行")
    void finishActionLastInSameRoundAllowed() {
        // R1: API tool_calls [player_action_list, finish_action] — finish 在末尾 ✓
        fakeLlm.addToolCallsResponse(List.of(
                new LlmToolCall("call_001", "player_action_list",
                        Map.of("branchId", "branch.b0000-start")),
                new LlmToolCall("call_002", "finish_action",
                        Map.of("status", "success", "message", "查询完毕：当前回合暂无行动记录。"))
        ));

        var result = agent.chatWithContextSession(
                "# Base\nbranch: branch.b0000-start\n",
                List.of(), "查看玩家行动");

        assertTrue(result.success(),
                "finish_action 在末尾应成功结束: " + result.errorMessage());
        assertEquals(2, result.toolCalls().size(),
                "两个工具都应被执行");
        assertEquals("player_action_list", result.toolCalls().get(0).tool());
        assertEquals("finish_action", result.toolCalls().get(1).tool());
        assertEquals("查询完毕：当前回合暂无行动记录。", result.finalText());
    }

    @Test
    @DisplayName("[finish_action, echo] 同轮 → finish_action 不在末尾 → 拒绝")
    void finishActionNotLastRejected() {
        toolRegistry.register(new EchoTool());
        // R1: [finish_action, echo] — finish 不在末尾 → REJECT
        fakeLlm.addToolCallsResponse(List.of(
                new LlmToolCall("call_001", "finish_action",
                        Map.of("status", "success", "message", "done.")),
                new LlmToolCall("call_002", "echo", Map.of("message", "hello"))
        ));
        // R2: 重写 — [echo, finish_action] — finish 在末尾 ✓
        fakeLlm.addToolCallsResponse(List.of(
                new LlmToolCall("call_003", "echo", Map.of("message", "hello")),
                new LlmToolCall("call_004", "finish_action",
                        Map.of("status", "success", "message", "done correctly."))
        ));

        var result = agent.chatWithContextSession(
                "# Base\nbranch: branch.b0000-start\n",
                List.of(), "测试");

        assertTrue(result.success(),
                "R2 重写后 finish_action 在末尾应成功: " + result.errorMessage());
        // R1 全部未执行，R2 执行了 echo + finish_action
        assertEquals(2, result.toolCalls().size(),
                "R2 的 echo 和 finish_action 都应被执行");
        assertEquals("echo", result.toolCalls().get(0).tool());
        assertEquals("finish_action", result.toolCalls().get(1).tool());

        // 验证 R1 触发了 FINISH_ACTION_NOT_LAST 拒绝事件
        boolean hasNotLastReject = sink.events.stream()
                .anyMatch(e -> AgentProgressEvent.FINISH_ACTION_REJECTED.equals(e.phase())
                        && e.detail().contains("FINISH_ACTION_NOT_LAST"));
        assertTrue(hasNotLastReject, "Should emit FINISH_ACTION_NOT_LAST rejection event");
    }

    @Test
    @DisplayName("[finish_action] 单独调用 → 正常结束")
    void finishActionAloneAllowed() {
        fakeLlm.addToolCallsResponse(List.of(
                new LlmToolCall("call_001", "finish_action",
                        Map.of("status", "success", "message", "操作完成。"))
        ));

        var result = agent.chatWithContextSession(
                "# Base\nbranch: branch.b0000-start\n",
                List.of(), "测试");

        assertTrue(result.success());
        assertEquals(1, result.toolCalls().size());
        assertEquals("finish_action", result.toolCalls().get(0).tool());
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
