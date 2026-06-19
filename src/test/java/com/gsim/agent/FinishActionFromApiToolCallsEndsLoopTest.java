package com.gsim.agent;

import com.gsim.agent.tool.FinishActionTool;
import com.gsim.llm.FakeLlmClient;
import com.gsim.llm.LlmToolCall;
import com.gsim.tool.ToolRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 验证 finish_action 通过 API tool_calls 路径被调用时正常结束 ToolLoop。
 * 确保 API native 路径下的控制流工具正常工作。
 */
@DisplayName("API tool_calls 的 finish_action 结束 ToolLoop")
class FinishActionFromApiToolCallsEndsLoopTest {

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
    @DisplayName("单次 API tool_calls finish_action → 立即结束")
    void singleApiFinishActionEndsLoopImmediately() {
        fakeLlm.addToolCallsResponse(List.of(
                new LlmToolCall("call_001", "finish_action",
                        Map.of("status", "success", "message", "状态正常，已通过 API tool_calls 完成。"))
        ));

        var result = agent.chatWithContextSession(
                "# Base\nbranch: branch.b0000-start\n",
                List.of(), "查看状态");

        assertTrue(result.success(),
                "API tool_calls finish_action should succeed: " + result.errorMessage());
        assertEquals(1, result.toolCalls().size());
        assertEquals("finish_action", result.toolCalls().get(0).tool());
        assertTrue(result.finalText().contains("API tool_calls 完成"),
                "finalText should be from API finish_action");
    }

    @Test
    @DisplayName("API tool_calls finish_action(status=partial) → 正常结束")
    void apiPartialFinishActionEndsLoop() {
        fakeLlm.addToolCallsResponse(List.of(
                new LlmToolCall("call_002", "finish_action",
                        Map.of("status", "partial", "message", "部分完成。"))
        ));

        var result = agent.chatWithContextSession(
                "# Base\nbranch: branch.b0000-start\n",
                List.of(), "查询");

        assertTrue(result.success());
        assertEquals(1, result.toolCalls().size());
        assertTrue(result.finalText().contains("部分完成"));
    }

    @Test
    @DisplayName("API tool_calls finish_action(status=failed) → 诚实结束")
    void apiFailedFinishActionEndsLoop() {
        fakeLlm.addToolCallsResponse(List.of(
                new LlmToolCall("call_003", "finish_action",
                        Map.of("status", "failed", "message", "操作失败：权限不足。"))
        ));

        var result = agent.chatWithContextSession(
                "# Base\nbranch: branch.b0000-start\n",
                List.of(), "执行操作");

        assertTrue(result.success());
        assertTrue(result.finalText().contains("权限不足"));
    }

    @Test
    @DisplayName("API finish_action message 不得包含 [工具结果] 伪标记 → 被拒绝")
    void apiFinishActionRejectsToolResultMarkerInMessage() {
        fakeLlm.addToolCallsResponse(List.of(
                new LlmToolCall("call_004", "finish_action",
                        Map.of("status", "success", "message", "[工具结果] 已完成。"))
        ));
        // 第二轮: 合法 finish_action（收到拒绝后）
        fakeLlm.addResponse("{\"tool\":\"finish_action\",\"args\":{\"status\":\"success\","
                + "\"message\":\"已完成，使用了清洁格式。\"}}");

        var result = agent.chatWithContextSession(
                "# Base\nbranch: branch.b0000-start\n",
                List.of(), "测试");

        assertTrue(result.success(),
                "Should recover after rejected finish_action: " + result.errorMessage());
        assertFalse(result.finalText().contains("[工具结果]"),
                "finalText should be clean");
    }

    @Test
    @DisplayName("API finish_action message 不得包含 fenced JSON → 被拒绝")
    void apiFinishActionRejectsFencedJsonInMessage() {
        fakeLlm.addToolCallsResponse(List.of(
                new LlmToolCall("call_005", "finish_action",
                        Map.of("status", "success", "message", "```json\n{\"tool\":\"echo\"}\n```完成。"))
        ));
        // 第二轮: 合法 finish_action
        fakeLlm.addResponse("{\"tool\":\"finish_action\",\"args\":{\"status\":\"success\","
                + "\"message\":\"已完成。\"}}");

        var result = agent.chatWithContextSession(
                "# Base\nbranch: branch.b0000-start\n",
                List.of(), "测试");

        assertTrue(result.success(),
                "Should recover after fenced JSON rejection");
        assertFalse(result.finalText().contains("```"),
                "finalText should contain no fenced blocks");
    }

    @Test
    @DisplayName("API finish_action 不允许在无 write 工具时宣称'已保存' → 被拒绝")
    void apiFinishActionRejectsFalseSuccessClaim() {
        fakeLlm.addToolCallsResponse(List.of(
                new LlmToolCall("call_006", "finish_action",
                        Map.of("status", "success", "message", "结算已保存，回合已推进。"))
        ));
        // 第二轮: 诚实 finish_action
        fakeLlm.addResponse("{\"tool\":\"finish_action\",\"args\":{\"status\":\"success\","
                + "\"message\":\"抱歉，我方无法执行保存操作。\"}}");

        var result = agent.chatWithContextSession(
                "# Base\nbranch: branch.b0000-start\n",
                List.of(), "结算");

        assertTrue(result.success(),
                "Should recover after false claim rejection");
        assertFalse(result.finalText().contains("已保存"),
                "finalText should not make false success claims");
    }
}
