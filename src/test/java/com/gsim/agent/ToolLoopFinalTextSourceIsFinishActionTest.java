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
 * 验证 finalText 的来源确为 finish_action.message，不是其他路径。
 */
@DisplayName("ToolLoop finalText source 为 finish_action")
class ToolLoopFinalTextSourceIsFinishActionTest {

    private FakeLlmClient fakeLlm;
    private ToolRegistry toolRegistry;
    private OrchestratorAgent agent;

    @BeforeEach
    void setUp() {
        fakeLlm = new FakeLlmClient();
        toolRegistry = new ToolRegistry();
        toolRegistry.register(new FinishActionTool());
        toolRegistry.register(new StubTool("player_action_list", "查询玩家行动记录。"));
        agent = new OrchestratorAgent(fakeLlm, toolRegistry, "test-model");
    }

    @Test
    @DisplayName("最终文本确为 finish_action.message")
    void finalTextEqualsFinishActionMessage() {
        fakeLlm.addToolCallsResponse(List.of(
                new LlmToolCall("call_001", "finish_action",
                        Map.of("status", "success", "message", "这是 finish_action 的最终文本。"))
        ));

        var result = agent.chatWithContextSession(
                "# Base\nbranch: branch.b0000-start\n",
                List.of(), "测试");

        assertTrue(result.success());
        assertEquals("这是 finish_action 的最终文本。", result.finalText(),
                "finalText must be exactly the finish_action.message");
        assertEquals("finish_action", result.toolCalls().get(0).tool());
    }

    @Test
    @DisplayName("player_action_list + finish_action 完整流程，finalText 来自 finish_action")
    void fullWorkflowFinalTextFromFinishAction() {
        // R1: player_action_list
        fakeLlm.addToolCallsResponse(List.of(
                new LlmToolCall("call_001", "player_action_list",
                        Map.of("branchId", "branch.b0000-start"))
        ));
        // R2: finish_action
        fakeLlm.addToolCallsResponse(List.of(
                new LlmToolCall("call_002", "finish_action",
                        Map.of("status", "success", "message", "当前回合无玩家行动记录。请继续。"))
        ));

        var result = agent.chatWithContextSession(
                "# Base\nbranch: branch.b0000-start\n",
                List.of(), "查看玩家行动");

        assertTrue(result.success());
        assertEquals("当前回合无玩家行动记录。请继续。", result.finalText(),
                "finalText must be exactly the finish_action message, not player_action_list output");

        // 工具调用记录中包含两个工具
        assertEquals(2, result.toolCalls().size());
        assertEquals("player_action_list", result.toolCalls().get(0).tool());
        assertEquals("finish_action", result.toolCalls().get(1).tool());

        // 不会出现第 3 轮
        assertEquals(2, fakeLlm.getRequestCount(),
                "No round 3 — finish_action ends the loop");
    }

    @Test
    @DisplayName("finish_action(status=partial) 的 message 也是 finalText")
    void partialStatusMessageIsFinalText() {
        fakeLlm.addToolCallsResponse(List.of(
                new LlmToolCall("call_001", "finish_action",
                        Map.of("status", "partial", "message", "部分完成：A 已查询，B 未能获取。"))
        ));

        var result = agent.chatWithContextSession(
                "# Base\nbranch: branch.b0000-start\n",
                List.of(), "查询 A 和 B");

        assertTrue(result.success());
        assertEquals("部分完成：A 已查询，B 未能获取。", result.finalText(),
                "partial status message should be the finalText");
    }

    @Test
    @DisplayName("无 finish_action 时不应有 finalText = null 以外的错误路径")
    void withoutFinishActionReturnsError() {
        // 只返回普通文本，不调用 finish_action
        fakeLlm.addResponse("系统状态正常。");
        fakeLlm.addResponse("一切就绪。");

        var result = agent.chatWithContextSession(
                "# Base\nbranch: branch.b0000-start\n",
                List.of(), "检查状态");

        assertFalse(result.success(),
                "Without finish_action, result should not be success");
        assertNotNull(result.errorMessage(),
                "Error message should indicate missing finish_action");
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
}
