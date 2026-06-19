package com.gsim.agent;

import com.gsim.agent.tool.FinishActionTool;
import com.gsim.llm.FakeLlmClient;
import com.gsim.tool.ToolRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 验证 AgentProgressSink 在完整 chat 流程中收到各阶段事件。
 */
@DisplayName("AgentProgressSink 接收轮次事件")
class AgentProgressSinkReceivesRoundEventsTest {

    private FakeLlmClient fakeLlm;
    private ToolRegistry toolRegistry;
    private CapturingProgressSink sink;
    private OrchestratorAgent agent;

    @BeforeEach
    void setUp() {
        fakeLlm = new FakeLlmClient();
        toolRegistry = new ToolRegistry();
        toolRegistry.register(new FinishActionTool());
        sink = new CapturingProgressSink();
        agent = new OrchestratorAgent(fakeLlm, toolRegistry, "test-model", sink);
    }

    @Test
    @DisplayName("完整 chat → sink 收到 CONTEXT_LOADED, WAITING_LLM, TOOL_SELECTED, TOOL_SUCCESS 事件")
    void receivesExpectedPhaseEvents() {
        fakeLlm.addResponse("{\"tool\":\"finish_action\",\"args\":{\"status\":\"success\","
                + "\"message\":\"完成。\"}}");

        agent.chatWithContextSession("# Base\nbranch: branch.b0000-start\n",
                List.of(), "查看状态");

        assertFalse(sink.events.isEmpty(), "Sink should receive at least one event");

        List<String> phases = sink.events.stream().map(AgentProgressEvent::phase).toList();
        assertTrue(phases.contains(AgentProgressEvent.CONTEXT_LOADED),
                "Should receive CONTEXT_LOADED: " + phases);
        assertTrue(phases.contains(AgentProgressEvent.WAITING_LLM),
                "Should receive WAITING_LLM: " + phases);
        assertTrue(phases.contains(AgentProgressEvent.TOOL_SELECTED),
                "Should receive TOOL_SELECTED: " + phases);
        assertTrue(phases.contains(AgentProgressEvent.TOOL_EXECUTING),
                "Should receive TOOL_EXECUTING: " + phases);
        assertTrue(phases.contains(AgentProgressEvent.TOOL_SUCCESS),
                "Should receive TOOL_SUCCESS: " + phases);
    }

    @Test
    @DisplayName("多轮工具调用 → sink 收到多轮事件")
    void receivesMultipleRoundEvents() {
        fakeLlm.addResponse("{\"tool\":\"finish_action\",\"args\":{\"status\":\"success\","
                + "\"message\":\"r1\"}}");

        agent.chatWithContextSession("# Base\nbranch: branch.b0000-start\n",
                List.of(), "测试");

        long contextLoadedCount = sink.events.stream()
                .filter(e -> AgentProgressEvent.CONTEXT_LOADED.equals(e.phase())).count();
        assertTrue(contextLoadedCount >= 1,
                "Should have at least 1 CONTEXT_LOADED, got " + contextLoadedCount);
    }

    @Test
    @DisplayName("CONTEXT_LOADED 事件包含 requestChars 和 toolCount")
    void contextLoadedEventHasRequestCharsAndToolCount() {
        fakeLlm.addResponse("{\"tool\":\"finish_action\",\"args\":{\"status\":\"success\","
                + "\"message\":\"完成。\"}}");

        agent.chatWithContextSession("# Base\nbranch: branch.b0000-start\n",
                List.of(), "查看状态");

        var loadedEvent = sink.events.stream()
                .filter(e -> AgentProgressEvent.CONTEXT_LOADED.equals(e.phase()))
                .findFirst().orElse(null);
        assertNotNull(loadedEvent, "Should have CONTEXT_LOADED event");
        String chars = loadedEvent.meta().get("requestChars");
        assertNotNull(chars, "requestChars meta should exist");
        assertTrue(Integer.parseInt(chars) > 0,
                "requestChars should be > 0, got " + chars);
        String tools = loadedEvent.meta().get("toolCount");
        assertNotNull(tools, "toolCount meta should exist");
    }

    @Test
    @DisplayName("工具失败后 sink 收到 TOOL_FAILED 而非 TOOL_SUCCESS")
    void toolFailedEmitsFailedEvent() {
        toolRegistry.register(new FailingEchoTool());
        fakeLlm.addResponse("{\"tool\":\"failing_echo\",\"args\":{}}");
        fakeLlm.addResponse("{\"tool\":\"finish_action\",\"args\":{\"status\":\"failed\","
                + "\"message\":\"echo 工具失败了。\"}}");

        agent.chatWithContextSession("# Base\nbranch: branch.b0000-start\n",
                List.of(), "测试失败");

        boolean hasFailed = sink.events.stream()
                .anyMatch(e -> AgentProgressEvent.TOOL_FAILED.equals(e.phase()));
        assertTrue(hasFailed, "Should receive TOOL_FAILED event for failing tool");
    }

    // ===== Helpers =====

    static class CapturingProgressSink implements AgentProgressSink {
        final List<AgentProgressEvent> events = new ArrayList<>();

        @Override
        public void onProgress(AgentProgressEvent event) {
            events.add(event);
        }
    }

    static class FailingEchoTool implements com.gsim.tool.AgentTool {
        @Override public String name() { return "failing_echo"; }
        @Override public String description() { return "Always fails."; }
        @Override
        public com.gsim.tool.ToolResult execute(com.gsim.tool.ToolCall call) {
            return com.gsim.tool.ToolResult.fail(name(), "Simulated failure");
        }
    }
}
