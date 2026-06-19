package com.gsim.agent;

import com.gsim.agent.tool.FinishActionTool;
import com.gsim.llm.FakeLlmClient;
import com.gsim.tool.ToolRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 验证非法方括号工具调用时 sink 收到 INVALID_BRACKET_INTENT 事件。
 */
@DisplayName("ToolLoop 检测非法方括号意图并发送进度事件")
class ToolLoopDetectsInvalidBracketToolIntentProgressTest {

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
    @DisplayName("[调用 player_action_list] → sink 收到 INVALID_BRACKET_INTENT")
    void bracketInvokeEmitsInvalidBracketIntent() {
        // Round 1: 非法方括号
        fakeLlm.addResponse("[调用 player_action_list] {\"branchId\":\"branch.b0002\"}");
        // 后续默认 "{}" 会导致 no-tool abort

        agent.chatWithContextSession(
                "# Base\nbranch: branch.b0002\n",
                List.of(), "查询玩家行动");

        boolean hasInvalidBracket = sink.events.stream()
                .anyMatch(e -> AgentProgressEvent.INVALID_BRACKET_INTENT.equals(e.phase()));
        assertTrue(hasInvalidBracket,
                "Should have received INVALID_BRACKET_INTENT event for bracket invoke");
    }

    @Test
    @DisplayName("合法 JSON finish_action → 不收到 INVALID_BRACKET_INTENT")
    void validJsonDoesNotEmitInvalidBracket() {
        fakeLlm.addResponse("{\"tool\":\"finish_action\",\"args\":{\"status\":\"success\","
                + "\"message\":\"完成。\"}}");

        agent.chatWithContextSession(
                "# Base\nbranch: branch.b0000-start\n",
                List.of(), "测试");

        boolean hasInvalidBracket = sink.events.stream()
                .anyMatch(e -> AgentProgressEvent.INVALID_BRACKET_INTENT.equals(e.phase()));
        assertFalse(hasInvalidBracket,
                "Valid JSON should not emit INVALID_BRACKET_INTENT event");
    }

    @Test
    @DisplayName("[工具结果] 伪输出 → INVALID_BRACKET_INTENT 事件")
    void toolResultMarkerEmitsInvalidBracket() {
        fakeLlm.addResponse("[工具结果] player_action_list 已返回 3 条记录。");

        agent.chatWithContextSession(
                "# Base\nbranch: branch.b0000-start\n",
                List.of(), "查询");

        boolean hasInvalid = sink.events.stream()
                .anyMatch(e -> AgentProgressEvent.INVALID_BRACKET_INTENT.equals(e.phase()));
        assertTrue(hasInvalid,
                "Should have INVALID_BRACKET_INTENT for [工具结果] marker");
    }

    @Test
    @DisplayName("口头'调用 xxx 工具' → INVALID_BRACKET_INTENT 事件")
    void verbalToolIntentEmitsInvalidBracket() {
        fakeLlm.addResponse("让我调用 player_action_list 工具查询数据...");

        agent.chatWithContextSession(
                "# Base\nbranch: branch.b0002\n",
                List.of(), "查询玩家行动");

        boolean hasInvalid = sink.events.stream()
                .anyMatch(e -> AgentProgressEvent.INVALID_BRACKET_INTENT.equals(e.phase()));
        assertTrue(hasInvalid,
                "Should have INVALID_BRACKET_INTENT for verbal tool intent");
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
