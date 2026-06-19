package com.gsim.agent;

import com.gsim.agent.tool.ConsolePrintTool;
import com.gsim.llm.FakeLlmClient;
import com.gsim.llm.LlmToolCall;
import com.gsim.tool.ToolRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 验证 console_print 与 finish_action 同轮混用时，按 finish_action 混用规则拒绝。
 * finish_action 必须单独调用，console_print 不例外。
 */
@DisplayName("console_print 与 finish_action 同轮混用被拒绝")
class ConsolePrintAndFinishActionSameRoundRejectedTest {

    private FakeLlmClient fakeLlm;
    private ToolRegistry toolRegistry;
    private CapturingProgressSink progressSink;
    private OrchestratorAgent agent;

    @BeforeEach
    void setUp() {
        fakeLlm = new FakeLlmClient();
        progressSink = new CapturingProgressSink();
        toolRegistry = new ToolRegistry();
        toolRegistry.register(new ConsolePrintTool(progressSink));
        toolRegistry.register(new com.gsim.agent.tool.FinishActionTool());
        agent = new OrchestratorAgent(fakeLlm, toolRegistry, "test-model", progressSink);
    }

    @Test
    @DisplayName("同轮 [console_print, finish_action] → 均不执行 → 继续")
    void bothToolsSameRoundRejectedNoneExecuted() {
        // R1: 同轮返回 console_print + finish_action（混用）
        fakeLlm.addToolCallsResponse(List.of(
                new LlmToolCall("non-stream", "console_print",
                        Map.of("message", "报名表模板...")),
                new LlmToolCall("non-stream", "finish_action",
                        Map.of("status", "success",
                                "message", "完成。"))));

        // R2: 收到拒绝后，只调用 console_print
        fakeLlm.addToolCallsResponse(List.of(
                new LlmToolCall("non-stream", "console_print",
                        Map.of("message", "报名表模板..."))));

        // R3: 单独调用 finish_action
        fakeLlm.addToolCallsResponse(List.of(
                new LlmToolCall("non-stream", "finish_action",
                        Map.of("status", "success",
                                "message", "报名表已显示在上方。请复制填写。"))));

        var result = agent.chatWithContextSession(
                "# Base\nbranch: branch.b0000-start\n",
                List.of(), "生成报名表");

        assertTrue(result.success(), "Should eventually succeed");
        // R1 的工具都不应被执行（同轮混用被拒绝）
        assertEquals(2, result.toolCalls().size(),
                "Only R2 console_print + R3 finish_action should be executed, was: "
                        + result.toolCalls());
        // 应收到 FINISH_ACTION_REJECTED 进度事件
        boolean hasRejected = progressSink.events.stream()
                .anyMatch(e -> AgentProgressEvent.FINISH_ACTION_REJECTED.equals(e.phase()));
        assertTrue(hasRejected, "Should have FINISH_ACTION_REJECTED event");
    }

    // ===== Helper =====

    static class CapturingProgressSink implements AgentProgressSink {
        final List<AgentProgressEvent> events = new ArrayList<>();
        @Override
        public void onProgress(AgentProgressEvent event) {
            events.add(event);
        }
    }
}
