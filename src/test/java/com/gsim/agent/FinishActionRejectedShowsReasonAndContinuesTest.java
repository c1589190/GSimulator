package com.gsim.agent;

import com.gsim.agent.tool.FinishActionTool;
import com.gsim.llm.FakeLlmManager;
import com.gsim.llm.LlmToolCall;
import com.gsim.tool.ToolRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 验证 finish_action 被拒绝后，progressSink 明确显示拒绝原因，并继续回灌给模型。
 */
@DisplayName("finish_action 拒绝后显原因并继续")
class FinishActionRejectedShowsReasonAndContinuesTest {

    private FakeLlmManager fakeLlm;
    private ToolRegistry toolRegistry;
    private CapturingProgressSink sink;
    private OrchestratorAgent agent;

    @BeforeEach
    void setUp() {
        fakeLlm = new FakeLlmManager();
        toolRegistry = new ToolRegistry();
        toolRegistry.register(new FinishActionTool());
        sink = new CapturingProgressSink();
        agent = new OrchestratorAgent(fakeLlm, toolRegistry, "test-model", sink);
    }

    @Test
    @DisplayName("R1 finish_action 含 [工具结果] → 拒绝原因可见 → R2 合法 → 结束")
    void rejectedShowsReasonAndContinues() {
        // R1: finish_action message 含 [工具结果] 标记 → 应被拒
        fakeLlm.addToolCallsResponse(List.of(
                new LlmToolCall("call_001", "finish_action",
                        Map.of("status", "success", "message", "[工具结果] 已查询完毕。"))
        ));
        // R2: 合法 finish_action
        fakeLlm.addToolCallsResponse(List.of(
                new LlmToolCall("call_002", "finish_action",
                        Map.of("status", "success", "message", "查询完毕，没有记录。"))
        ));

        var result = agent.chatWithContextSession(
                "# Base\nbranch: branch.b0000-start\n",
                List.of(), "查询");

        assertTrue(result.success(),
                "Should recover after rejection: " + result.errorMessage());

        // 进度事件中必须有被拒原因
        List<AgentProgressEvent> rejected = sink.events.stream()
                .filter(e -> AgentProgressEvent.FINISH_ACTION_REJECTED.equals(e.phase()))
                .toList();
        assertFalse(rejected.isEmpty(),
                "Should have at least one FINISH_ACTION_REJECTED event");
        String firstReject = rejected.get(0).detail();
        assertTrue(firstReject.contains("BANNED_CONTENT")
                        || firstReject.contains("rejected"),
                "Reject reason should be visible in progress: " + firstReject);

        // 最终结果清洁
        assertFalse(result.finalText().contains("[工具结果]"),
                "finalText should NOT contain banned markers");
        assertEquals(2, fakeLlm.getRequestCount(),
                "Should have 2 LLM rounds (reject + retry)");
    }

    @Test
    @DisplayName("R1 finish_action 含 fenced JSON → 拒绝 → R2 合法")
    void fencedJsonRejectionShowsReason() {
        fakeLlm.addToolCallsResponse(List.of(
                new LlmToolCall("call_001", "finish_action",
                        Map.of("status", "success",
                                "message", "```json\n{\"tool\":\"echo\"}\n```完成。"))
        ));
        fakeLlm.addToolCallsResponse(List.of(
                new LlmToolCall("call_002", "finish_action",
                        Map.of("status", "success", "message", "清洁完成。"))
        ));

        var result = agent.chatWithContextSession(
                "# Base\nbranch: branch.b0000-start\n",
                List.of(), "测试");

        assertTrue(result.success(),
                "Should recover: " + result.errorMessage());
        assertFalse(result.finalText().contains("```"),
                "finalText should be clean");

        boolean hasReject = sink.events.stream()
                .anyMatch(e -> AgentProgressEvent.FINISH_ACTION_REJECTED.equals(e.phase()));
        assertTrue(hasReject, "Should have progress event for rejection");
    }

    // ===== Helper =====

    static class CapturingProgressSink implements AgentProgressSink {
        final List<AgentProgressEvent> events = new java.util.ArrayList<>();
        @Override
        public void onProgress(AgentProgressEvent event) {
            events.add(event);
        }
    }
}
