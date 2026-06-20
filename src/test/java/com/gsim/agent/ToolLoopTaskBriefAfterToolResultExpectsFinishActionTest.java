package com.gsim.agent;

import com.gsim.agent.tool.FinishActionTool;
import com.gsim.llm.FakeLlmManager;
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
 * 验证工具成功执行后，TaskBrief 期望 FINISH_ACTION。
 */
@DisplayName("TaskBrief 工具成功后期望 FINISH_ACTION")
class ToolLoopTaskBriefAfterToolResultExpectsFinishActionTest {

    private FakeLlmManager fakeLlm;
    private ToolRegistry toolRegistry;
    private CapturingProgressSink sink;
    private OrchestratorAgent agent;

    @BeforeEach
    void setUp() {
        fakeLlm = new FakeLlmManager();
        toolRegistry = new ToolRegistry();
        toolRegistry.register(new EchoTool());
        toolRegistry.register(new FinishActionTool());
        sink = new CapturingProgressSink();
        agent = new OrchestratorAgent(fakeLlm, toolRegistry, "test-model", sink);
    }

    @Test
    @DisplayName("echo 成功 → 下一轮 finish_action → sink 收到 TOOL_SUCCESS 后 AWAITING_FINISH")
    void afterEchoToolNextRoundExpectsFinish() {
        fakeLlm.addResponse("{\"tool\":\"echo\",\"args\":{\"message\":\"r1\"}}");
        fakeLlm.addResponse("{\"tool\":\"finish_action\",\"args\":{\"status\":\"success\","
                + "\"message\":\"echo 执行完成，结果如上。\"}}");

        agent.chatWithContextSession("# Base\nbranch: branch.b0000-start\n",
                List.of(), "测试回音");

        // 至少有两个工具成功事件
        List<AgentProgressEvent> successEvents = sink.events.stream()
                .filter(e -> AgentProgressEvent.TOOL_SUCCESS.equals(e.phase()))
                .toList();
        assertEquals(2, successEvents.size(),
                "Should have 2 TOOL_SUCCESS events for echo + finish_action");
        assertEquals("echo", successEvents.get(0).meta().get("tool"));
        assertEquals("finish_action", successEvents.get(1).meta().get("tool"));
    }

    @Test
    @DisplayName("仅 finish_action 单工具 → 1 个 TOOL_SUCCESS")
    void singleFinishActionGivesOneSuccess() {
        fakeLlm.addResponse("{\"tool\":\"finish_action\",\"args\":{\"status\":\"success\","
                + "\"message\":\"直接完成。\"}}");

        agent.chatWithContextSession("# Base\nbranch: branch.b0000-start\n",
                List.of(), "测试");

        var successEvents = sink.events.stream()
                .filter(e -> AgentProgressEvent.TOOL_SUCCESS.equals(e.phase()))
                .toList();
        assertEquals(1, successEvents.size());
        assertEquals("finish_action", successEvents.get(0).meta().get("tool"));
    }

    // ===== Helpers =====

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
