package com.gsim.agent;

import com.gsim.llm.FakeLlmManager;
import com.gsim.llm.LlmRequest;
import com.gsim.llm.ToolDef;
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
 * 验证 ToolLoop 必须显式调用 finish_action 才能结束。
 * 纯自然语言（无 tool call）不会结束循环，而是触发提醒并继续。
 */
@DisplayName("ToolLoop 必须显式 finish_action 才能结束")
class ToolLoopRequiresFinishActionToEndTest {

    private FakeLlmManager fakeLlm;
    private ToolRegistry toolRegistry;
    private OrchestratorAgent agent;

    @BeforeEach
    void setUp() {
        fakeLlm = new FakeLlmManager();
        toolRegistry = new ToolRegistry();
        toolRegistry.register(new EchoTool());
        toolRegistry.register(new com.gsim.agent.tool.FinishActionTool());
        agent = new OrchestratorAgent(fakeLlm, toolRegistry, "test-model");
    }

    @Test
    @DisplayName("纯自然语言 → 不被视为结束 → 触发提醒 → finish_action → 结束")
    void plainTextWithoutFinishActionTriggersReminder() {
        // 第一轮：纯自然语言，无 tool call
        fakeLlm.addResponse("当前系统状态正常，一切都准备好了。");
        // 第二轮：收到提醒后调用 finish_action
        fakeLlm.addResponse("{\"tool\":\"finish_action\",\"args\":{\"status\":\"success\","
                + "\"message\":\"当前系统状态正常。你可以继续操作。\"}}");

        var result = agent.chatWithContextSession(
                "# Base\nbranch: branch.b0000-start\n",
                List.of(), "查看状态");

        assertTrue(result.success());
        assertEquals(1, result.toolCalls().size(),
                "Only the finish_action call should be recorded");
        assertEquals("finish_action", result.toolCalls().get(0).tool());
        assertFalse(result.finalText().contains("你还没有调用 finish_action"),
                "Final text should NOT contain the reminder — it's the finish_action message");
    }

    @Test
    @DisplayName("空 JSON 响应 {} → 无工具提取 → 提醒 → finish_action → 结束")
    void emptyJsonTriggersReminderThenFinishAction() {
        // 第一轮：空 JSON — no tool → reminder
        fakeLlm.addResponse("{}");
        // 第二轮：finish_action
        fakeLlm.addResponse("{\"tool\":\"finish_action\",\"args\":{\"status\":\"success\","
                + "\"message\":\"状态正常。\"}}");

        var result = agent.chatWithContextSession(
                "# Base\nbranch: branch.b0000-start\n",
                List.of(), "状态");

        assertTrue(result.success());
        assertEquals(1, result.toolCalls().size());
        assertEquals("finish_action", result.toolCalls().get(0).tool());
    }

    @Test
    @DisplayName("echo 工具后仍需要 finish_action → 不结束")
    void toolCallWithoutFinishActionContinues() {
        // 第一轮：echo 工具调用
        fakeLlm.addResponse("{\"tool\":\"echo\",\"args\":{\"message\":\"hello\"}}");
        // 第二轮：自然语言（无 finish_action）→ 提醒
        fakeLlm.addResponse("工具已执行完毕。");
        // 第三轮：finish_action
        fakeLlm.addResponse("{\"tool\":\"finish_action\",\"args\":{\"status\":\"success\","
                + "\"message\":\"工具执行完毕，状态正常。\"}}");

        var result = agent.chatWithContextSession(
                "# Base\nbranch: branch.b0000-start\n",
                List.of(), "测试");

        assertTrue(result.success());
        assertEquals(2, result.toolCalls().size(),
                "echo + finish_action = 2 tool calls (reminder round has no tool)");
    }

    // ===== Test 4: R1 纯文本 → R2 正常（不再强制 tool_choice）=====

    @Test
    @DisplayName("R1 纯文本 → R2 保留全部工具且 tool_choice=auto（不再强制 finish_action）")
    void secondRoundHasOnlyFinishActionWithForcedToolChoice() {
        fakeLlm.addResponse("当前系统状态正常。");
        fakeLlm.addResponse("{\"tool\":\"finish_action\",\"args\":{\"status\":\"success\","
                + "\"message\":\"系统状态正常，你可以继续操作。\"}}");

        var result = agent.chatWithContextSession(
                "# Base\nbranch: branch.b0000-start\n",
                List.of(), "查看状态");

        assertTrue(result.success());
        assertEquals(1, result.toolCalls().size());
        assertEquals("finish_action", result.toolCalls().get(0).tool());

        // 验证 R1 和 R2 request 属性
        var requests = fakeLlm.getCapturedRequests();
        assertTrue(requests.size() >= 2, "至少 2 轮 LLM 请求");

        LlmRequest r1 = requests.get(0);
        LlmRequest r2 = requests.get(1);

        // R1: 包含注册的工具（echo + finish_action 至少 2 个），tool_choice = "auto"
        assertTrue(r1.tools().size() >= 2,
                "R1 应包含 echo + finish_action 工具: " + r1.tools().stream().map(ToolDef::name).toList());
        assertEquals("auto", r1.toolChoice());

        // R2: 保留全部工具（不再强制只有 finish_action），tool_choice = "auto"
        assertTrue(r2.tools().size() >= 2,
                "R2 应保留全部工具: " + r2.tools().stream().map(ToolDef::name).toList());
        assertEquals("auto", r2.toolChoice(),
                "R2 tool_choice 应为 auto（不再强制）");
    }

    // ===== Test 5: 连续 3 轮纯文本 → 提前中止（不再 auto-wrap）=====

    @Test
    @DisplayName("连续 3 轮纯文本 → 第 3 轮触发中止（不再 auto-wrap）")
    void autoWrapPlainContentWhenForcedFinishActionRoundStillReturnsPlainText() {
        fakeLlm.addResponse("第一轮纯文本。");
        fakeLlm.addResponse("第二轮纯文本。");
        fakeLlm.addResponse("第三轮纯文本。");

        var result = agent.chatWithContextSession(
                "# Base\nbranch: branch.b0000-start\n",
                List.of(), "test");

        assertFalse(result.success(),
                "连续 3 轮纯文本应触发中止，不再 auto-wrap");
        assertTrue(result.errorMessage() != null
                        && result.errorMessage().contains("no tool calls"),
                "错误消息应提到连续无工具轮数: " + result.errorMessage());
        assertEquals(0, result.toolCalls().size(),
                "不应有任何工具调用记录");
    }

    // ===== Stub =====

    static class EchoTool implements AgentTool {
        @Override public String name() { return "echo"; }
        @Override public String description() { return "Echo tool."; }
        @Override
        public ToolResult execute(ToolCall call) {
            return ToolResult.ok(name(), List.of(
                    new ToolResult.Item("echo", "test", "ok", 1.0)));
        }
    }
}
