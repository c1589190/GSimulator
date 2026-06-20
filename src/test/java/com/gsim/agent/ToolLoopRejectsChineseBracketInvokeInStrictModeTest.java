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
 * 验证中文方括号工具调用格式 [调用 xxx] 被拒绝并打回。
 * 括号调用不是工具执行路径 —— 必须被 isInvalidToolIntent 检测到并拒绝。
 */
@DisplayName("ToolLoop 拒绝中文方括号工具调用")
class ToolLoopRejectsChineseBracketInvokeInStrictModeTest {

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
    @DisplayName("[调用 player_action_list] → 被检测为非法，不被执行 → reprompt 后 finish_action 成功")
    void bracketInvokeDetectedAndNotExecuted() {
        // Round 1: 中文方括号格式（非法）→ INVALID_TOOL_INTENT → reprompt
        fakeLlm.addResponse("[调用 player_action_list] {\"branchId\":\"branch.b0002\"}");
        // Round 2: 收到 reprompt 后调用 finish_action 结束
        fakeLlm.addResponse("{\"tool\":\"finish_action\",\"args\":{\"status\":\"success\","
                + "\"message\":\"已收到纠正提示，无法执行 bracket 格式的工具调用。\"}}");

        var result = agent.chatWithContextSession(
                "# Base\nbranch: branch.b0002\n",
                List.of(), "查询玩家行动");

        assertTrue(result.success(),
                "收到 reprompt 后应能正常调用 finish_action 结束");
        assertEquals(1, result.toolCalls().size(),
                "只有 finish_action 被真正执行（bracket invoke 被跳过）");
        assertEquals("finish_action", result.toolCalls().get(0).tool());
    }

    @Test
    @DisplayName("[工具结果] 标记 → 被拒绝")
    void toolResultMarkerRejected() {
        fakeLlm.addResponse("[工具结果] player_action_list 返回了 3 条记录。");

        var result = agent.chatWithContextSession(
                "# Base\nbranch: branch.b0000-start\n",
                List.of(), "查看结果");

        assertFalse(result.success(),
                "Content with [工具结果] marker should be rejected");
    }

    @Test
    @DisplayName("{branchId=xxx} 非 JSON 格式 → 被拒绝")
    void nonJsonMapFormatRejected() {
        fakeLlm.addResponse("{branchId=b0002, mode=settlement}");

        var result = agent.chatWithContextSession(
                "# Base\nbranch: branch.b0000-start\n",
                List.of(), "查询");

        assertFalse(result.success(),
                "Non-JSON map format should be rejected");
    }

    @Test
    @DisplayName("合法 JSON tool call 仍在括号拒绝后执行 → 验证括号被跳过而不是整个 ToolLoop 崩溃")
    void validJsonAfterBracketInvokeSucceeds() {
        // Round 1: 括号非法 → reprompt
        fakeLlm.addResponse("[调用 player_action_list] {\"branchId\":\"branch.b0002\"}");
        // Round 2: 合法 JSON finish_action
        fakeLlm.addResponse("{\"tool\":\"finish_action\",\"args\":{\"status\":\"success\","
                + "\"message\":\"已收到纠正提示，改为合法 JSON 格式。\"}}");

        var result = agent.chatWithContextSession(
                "# Base\nbranch: branch.b0002\n",
                List.of(), "查询玩家行动");

        assertTrue(result.success(),
                "Agent should recover after bracket invoke reprompt: " + result.errorMessage());
        assertEquals(1, result.toolCalls().size(),
                "Only the valid finish_action should be executed");
        assertEquals("finish_action", result.toolCalls().get(0).tool());
        assertTrue(result.finalText().contains("合法 JSON"),
                "finalText should contain the second-round message");
    }
}
