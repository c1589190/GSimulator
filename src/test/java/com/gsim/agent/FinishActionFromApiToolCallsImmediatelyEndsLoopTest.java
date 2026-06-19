package com.gsim.agent;

import com.gsim.agent.tool.FinishActionTool;
import com.gsim.llm.FakeLlmClient;
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
 * 验证 finish_action 通过 API tool_calls 被调用后立即结束 ToolLoop，
 * 不得发起下一轮 LLM request。
 */
@DisplayName("API tool_calls finish_action 立即结束 ToolLoop")
class FinishActionFromApiToolCallsImmediatelyEndsLoopTest {

    private FakeLlmClient fakeLlm;
    private ToolRegistry toolRegistry;
    private OrchestratorAgent agent;

    @BeforeEach
    void setUp() {
        fakeLlm = new FakeLlmClient();
        toolRegistry = new ToolRegistry();
        toolRegistry.register(new FinishActionTool());
        agent = new OrchestratorAgent(fakeLlm, toolRegistry, "test-model");
    }

    @Test
    @DisplayName("单次 API tool_calls finish_action → 立即结束，不发下一轮请求")
    void singleApiFinishActionEndsLoopImmediately() {
        fakeLlm.addToolCallsResponse(List.of(
                new LlmToolCall("call_001", "finish_action",
                        Map.of("status", "success", "message", "收到。"))
        ));

        var result = agent.chatWithContextSession(
                "# Base\nbranch: branch.b0000-start\n",
                List.of(), "打个招呼");

        assertTrue(result.success(),
                "API tool_calls finish_action should succeed: " + result.errorMessage());
        assertEquals(1, result.toolCalls().size());
        assertEquals("finish_action", result.toolCalls().get(0).tool());
        assertEquals("收到。", result.finalText(),
                "finalText should be the finish_action message verbatim");

        // 关键：只发起了 1 次 LLM request，finish_action accepted 后没有下一轮
        assertEquals(1, fakeLlm.getRequestCount(),
                "Should NOT trigger next LLM request after finish_action accepted");
    }

    @Test
    @DisplayName("player_action_list → finish_action 两轮后结束，无第三轮")
    void playerActionListThenFinishActionEndsImmediately() {
        toolRegistry.register(new StubTool("player_action_list", "查询玩家行动记录。"));
        // R1: player_action_list
        fakeLlm.addToolCallsResponse(List.of(
                new LlmToolCall("call_001", "player_action_list",
                        Map.of("branchId", "branch.b0000-start"))
        ));
        // R2: finish_action
        fakeLlm.addToolCallsResponse(List.of(
                new LlmToolCall("call_002", "finish_action",
                        Map.of("status", "success", "message", "当前回合暂无玩家行动记录。"))
        ));

        var result = agent.chatWithContextSession(
                "# Base\nbranch: branch.b0000-start\n",
                List.of(), "查看玩家行动");

        assertTrue(result.success(),
                "Should succeed: " + result.errorMessage());
        assertEquals(2, result.toolCalls().size());
        assertEquals("player_action_list", result.toolCalls().get(0).tool());
        assertEquals("finish_action", result.toolCalls().get(1).tool());

        // 关键：只发起 2 次 LLM request，无第 3 轮
        assertEquals(2, fakeLlm.getRequestCount(),
                "Should have exactly 2 LLM requests, no round 3");
    }

    // ===== Stub =====

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
}
