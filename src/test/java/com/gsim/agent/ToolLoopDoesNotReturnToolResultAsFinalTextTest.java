package com.gsim.agent;

import com.gsim.llm.FakeLlmClient;
import com.gsim.llm.LlmMessage;
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
 * 验证 ToolLoop 修复（适配 finish_action 架构）：
 * <ol>
 * <li>tool_result 不作为 finalText 返回给用户</li>
 * <li>tool result 后 LLM 继续推理并调用 finish_action</li>
 * <li>finish_action.message 是自然语言，不含 raw JSON</li>
 * </ol>
 */
@DisplayName("ToolLoop 不将 ToolResult 作为 FinalText (finish_action)")
class ToolLoopDoesNotReturnToolResultAsFinalTextTest {

    private FakeLlmClient fakeLlm;
    private ToolRegistry toolRegistry;
    private OrchestratorAgent agent;

    @BeforeEach
    void setUp() {
        fakeLlm = new FakeLlmClient();
        toolRegistry = new ToolRegistry();
        toolRegistry.register(new RootStatusTool());
        toolRegistry.register(new com.gsim.agent.tool.FinishActionTool());
        agent = new OrchestratorAgent(fakeLlm, toolRegistry, "test-model");
    }

    // ========== 核心测试: 工具结果不作为 finalText ==========

    @Test
    @DisplayName("finish_action.message 是自然语言，不含工具结果")
    void toolResultNotInFinalText() {
        // 第一轮: LLM 返回 tool call (root_status)
        fakeLlm.addResponse("{\"tool\":\"root_status\",\"args\":{}}");
        // 第二轮: LLM 调用 finish_action
        fakeLlm.addResponse("{\"tool\":\"finish_action\",\"args\":{\"status\":\"success\","
                + "\"message\":\"当前处于根节点 branch.b0000-start。已准备好创建第一回合资料。\"}}");

        var result = agent.chatWithContextSession(
                "# BaseContext\n\nbranch: branch.b0000-start\n",
                List.of(),
                "检测一下你能不能创建下一回合资料");

        assertTrue(result.success(), "Result should be successful");
        assertEquals(2, result.toolCalls().size(), "Should have root_status + finish_action");
        assertEquals("root_status", result.toolCalls().get(0).tool());
        assertEquals("finish_action", result.toolCalls().get(1).tool());
        assertNotNull(result.finalText(), "finalText should not be null");
        assertFalse(result.finalText().contains("[TOOL_RESULT]"),
                "finalText must NOT contain [TOOL_RESULT] marker, got: " + result.finalText());
        assertFalse(result.finalText().contains("[工具"),
                "finalText must NOT contain tool result prefix, got: " + result.finalText());
        assertFalse(result.finalText().contains("{\"activeRoot\""),
                "finalText must NOT contain raw JSON, got: " + result.finalText());
        assertFalse(result.finalText().startsWith("{"),
                "finalText must NOT start with JSON, got: " + result.finalText());
        assertTrue(result.finalText().contains("根节点") || result.finalText().contains("branch"),
                "finalText should be natural language response, got: " + result.finalText());
    }

    // ========== root_status 后继续 LLM ==========

    @Test
    @DisplayName("root_status 调用后 ToolLoop 继续执行，以 finish_action 结束")
    void toolLoopContinuesAfterRootStatus() {
        // 第一轮: tool call for root_status
        fakeLlm.addResponse("{\"tool\":\"root_status\",\"args\":{}}");
        // 第二轮: 继续另一个 tool call
        fakeLlm.addResponse("{\"tool\":\"root_status\",\"args\":{}}");
        // 第三轮: finish_action
        fakeLlm.addResponse("{\"tool\":\"finish_action\",\"args\":{\"status\":\"success\","
                + "\"message\":\"确认完毕。当前状态正常。\"}}");

        var result = agent.chatWithContextSession(
                "# BaseContext\n\nbranch: branch.b0000-start\n",
                List.of(),
                "确认状态");

        assertTrue(result.success());
        assertEquals(3, result.toolCalls().size(),
                "Should have 3 tool calls (2 root_status + finish_action)");
        assertTrue(result.finalText().contains("确认完毕"),
                "Final answer should be finish_action message, got: " + result.finalText());
        assertFalse(result.finalText().contains("[TOOL_RESULT]"),
                "finalText must NOT contain tool result markers");
    }

    // ========== raw JSON 守卫（现通过 finish_action 验证） ==========

    @Test
    @DisplayName("finish_action.message 含有 raw JSON 时被拒绝并重试")
    void rawJsonGuardTriggersRetry() {
        // 第一轮: tool call
        fakeLlm.addResponse("{\"tool\":\"root_status\",\"args\":{}}");
        // 第二轮: LLM 错误地发送包含 raw JSON 的 finish_action.message → 被拒绝
        fakeLlm.addResponse("{\"tool\":\"finish_action\",\"args\":{\"status\":\"success\","
                + "\"message\":\"{\\\"activeRoot\\\":\\\"cna-rk\\\",\\\"activeBranch\\\":\\\"branch.b0000-start\\\"}\"}}");
        // 第三轮: 守卫触发后 LLM 用干净的自然语言重试 finish_action
        fakeLlm.addResponse("{\"tool\":\"finish_action\",\"args\":{\"status\":\"success\","
                + "\"message\":\"已确认：当前处于根节点 branch.b0000-start。准备好了。\"}}");

        var result = agent.chatWithContextSession(
                "# BaseContext\n\nbranch: branch.b0000-start\n",
                List.of(),
                "确认当前状态");

        assertTrue(result.success());
        assertEquals(3, result.toolCalls().size(),
                "Should have root_status + rejected finish_action + accepted finish_action");
        assertTrue(result.finalText().contains("branch.b0000-start") || result.finalText().contains("准备好了"),
                "finalText should be natural language after retry, got: " + result.finalText());
        assertFalse(result.finalText().contains("{\"activeRoot\""),
                "finalText must NOT contain raw JSON after guard");
        assertFalse(result.finalText().startsWith("{"),
                "finalText must NOT start with {");
        // 验证消息中包含守卫拒绝提示
        List<LlmMessage> messages = fakeLlm.getCapturedRequests().get(
                fakeLlm.getCapturedRequests().size() - 1).messages();
        boolean hasGuardRetry = messages.stream().anyMatch(m ->
                m.role().equals("user") && m.content().contains("裸 JSON"));
        assertTrue(hasGuardRetry, "Should have guard rejection message in LLM context: " + messages);
    }

    // ========== [TOOL_RESULT] 标记开头被守卫 ==========

    @Test
    @DisplayName("finish_action.message 以 [TOOL_RESULT] 开头被拒绝")
    void toolResultPrefixTriggersGuard() {
        fakeLlm.addResponse("{\"tool\":\"root_status\",\"args\":{}}");
        // finish_action 的 message 包含 [TOOL_RESULT] → 被拒绝
        fakeLlm.addResponse("{\"tool\":\"finish_action\",\"args\":{\"status\":\"success\","
                + "\"message\":\"[TOOL_RESULT]\\nsuccess: true\\ncontent:\\nactiveRoot: cna-rk\\n[/TOOL_RESULT]\"}}");
        // 重试
        fakeLlm.addResponse("{\"tool\":\"finish_action\",\"args\":{\"status\":\"success\","
                + "\"message\":\"当前处于 cna-rk 根节点。已准备好进入第一回合。\"}}");

        var result = agent.chatWithContextSession(
                "# BaseContext\n\nbranch: branch.b0000-start\n",
                List.of(),
                "确认状态");

        assertTrue(result.success());
        assertFalse(result.finalText().startsWith("[TOOL_RESULT]"),
                "finalText must NOT start with [TOOL_RESULT]: " + result.finalText());
    }

    // ========== buildToolResultFeedback 格式 ==========

    @Test
    @DisplayName("buildToolResultFeedback 使用 [TOOL_RESULT] 格式")
    void toolResultFeedbackFormat() {
        var result = ToolResult.ok("root_status", List.of(
                new ToolResult.Item("status", "cna-rk",
                        "activeRoot: cna-rk\nactiveBranch: branch.b0000-start\nisAtRootBranch: true", 1.0)));

        String feedback = OrchestratorAgent.buildToolResultFeedback("root_status", result);

        assertTrue(feedback.contains("[TOOL_RESULT]"),
                "Tool result feedback should start with [TOOL_RESULT], got: " + feedback);
        assertTrue(feedback.contains("[/TOOL_RESULT]"),
                "Tool result feedback should end with [/TOOL_RESULT], got: " + feedback);
        assertTrue(feedback.contains("请基于以上工具结果继续完成用户请求"),
                "Feedback should instruct LLM to continue, got: " + feedback);
        assertTrue(feedback.contains("tool: root_status"),
                "Feedback should contain tool name");
        assertTrue(feedback.contains("success: true"),
                "Feedback should contain success status");
    }

    // ========== isRawToolOutput 检测 ==========

    @Test
    @DisplayName("isRawToolOutput 正确检测 raw tool output")
    void isRawToolOutputDetection() {
        assertTrue(OrchestratorAgent.isRawToolOutput(
                "[TOOL_RESULT]\nsuccess: true\ncontent:\n...[/TOOL_RESULT]"),
                "[TOOL_RESULT] prefix should be detected");
        assertTrue(OrchestratorAgent.isRawToolOutput(
                "{\"activeRoot\":\"cna-rk\",\"activeBranch\":\"branch.b0000-start\"}"),
                "JSON without 'tool' field should be detected");
        assertTrue(OrchestratorAgent.isRawToolOutput(
                "[工具 result"),
                "[工具 prefix should be detected");

        assertFalse(OrchestratorAgent.isRawToolOutput(
                "当前处于根节点 branch.b0000-start。一切正常。"),
                "Natural language should NOT be detected as raw tool output");
        assertFalse(OrchestratorAgent.isRawToolOutput(
                "{\"tool\":\"root_status\",\"args\":{}}"),
                "Tool call JSON should NOT be detected (has 'tool' field)");
        assertFalse(OrchestratorAgent.isRawToolOutput(""),
                "Empty string should NOT be detected");
        assertFalse(OrchestratorAgent.isRawToolOutput(null),
                "null should NOT be detected");
    }

    // ========== 多轮工具调用后 finalText 清洁 ==========

    @Test
    @DisplayName("多轮工具调用后 finish_action.message 不含任何工具结果")
    void multipleToolCallsFinalTextClean() {
        fakeLlm.addResponse("{\"tool\":\"root_status\",\"args\":{}}");
        fakeLlm.addResponse("{\"tool\":\"root_status\",\"args\":{}}");
        fakeLlm.addResponse("{\"tool\":\"finish_action\",\"args\":{\"status\":\"success\","
                + "\"message\":\"经过两次确认，当前系统状态正常。可以开始推演。\"}}");

        var result = agent.chatWithContextSession(
                "# BaseContext\n\nbranch: branch.b0000-start\n",
                List.of(),
                "多次确认状态");

        assertTrue(result.success());
        assertEquals(3, result.toolCalls().size());
        String ft = result.finalText();
        assertFalse(ft.contains("[TOOL_RESULT]"));
        assertFalse(ft.contains("{\"activeRoot\""));
        assertFalse(ft.startsWith("{"));
        assertFalse(ft.startsWith("[工具"));
    }

    // ===== Fake Tool =====

    static class RootStatusTool implements AgentTool {
        @Override
        public String name() { return "root_status"; }

        @Override
        public String description() {
            return "查询当前 root 状态: active root ID, active branch ID, 是否在根节点, knowledge db path。";
        }

        @Override
        public ToolResult execute(ToolCall call) {
            String content = "activeRoot: cna-rk\n"
                    + "activeBranch: branch.b0000-start\n"
                    + "isAtRootBranch: true\n"
                    + "knowledgeDbPath: data/worlds/cna-rk/knowledge.db";
            return ToolResult.ok(name(), List.of(
                    new ToolResult.Item("root_status", "cna-rk", content, 1.0)));
        }
    }
}
