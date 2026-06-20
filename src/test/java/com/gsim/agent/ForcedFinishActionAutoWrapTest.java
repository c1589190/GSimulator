package com.gsim.agent;

import com.gsim.agent.tool.FinishActionTool;
import com.gsim.llm.FakeLlmManager;
import com.gsim.tool.ToolRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 验证 forced finish_action 阶段 auto-wrap 的各种场景。
 * 覆盖：pendingPlainContent 兜底、currentContent 优先、validation 拒绝、abort 顺序。
 */
@DisplayName("Forced finish_action auto-wrap 行为")
class ForcedFinishActionAutoWrapTest {

    private FakeLlmManager fakeLlm;
    private ToolRegistry toolRegistry;
    private OrchestratorAgent agent;

    @BeforeEach
    void setUp() {
        fakeLlm = new FakeLlmManager();
        toolRegistry = new ToolRegistry();
        toolRegistry.register(new FinishActionTool());
        agent = new OrchestratorAgent(fakeLlm, toolRegistry, "test-model");
    }

    // ===== Test 1: R2 空 content → 使用 R1 pendingPlainContent 兜底 =====

    @Test
    @DisplayName("R1 纯文本 → R2 forced 阶段返回空 content → auto-wrap 用 R1 pendingPlainContent")
    void emptyContentWrapsPreviousPlainAnswer() {
        // R1: 纯文本（触发 forcedFinishAction）
        fakeLlm.addResponse("说话");
        // R2: forced finish_action 阶段，provider 返回空 content
        fakeLlm.addResponse("");

        var result = agent.chatWithContextSession(
                "# Base\nbranch: branch.b0000-start\n",
                List.of(), "说话");

        assertTrue(result.success(),
                "R2 空 content 时应用 R1 pendingPlainContent 兜底: " + result.errorMessage());
        assertEquals("说话", result.finalText(),
                "finalText 应为 R1 的 pendingPlainContent");
        assertEquals(1, result.toolCalls().size(),
                "应记录 1 个 auto-wrapped finish_action");
        assertEquals("finish_action", result.toolCalls().get(0).tool());

        // 不应出现 consecutive no-tool abort
        assertFalse(result.errorMessage() != null
                        && result.errorMessage().contains("consecutive"),
                "不应触发 consecutive no-tool abort");
        assertFalse(result.errorMessage() != null
                        && result.errorMessage().contains("no tool calls"),
                "不应触发 no tool calls abort");
    }

    // ===== Test 2: R2 currentContent 优先于 R1 pendingPlainContent =====

    @Test
    @DisplayName("R2 forced 阶段有新内容 → 优先用 currentContent，不用 pendingPlainContent")
    void currentContentPreferredOverPending() {
        // R1: 旧答案
        fakeLlm.addResponse("旧答案");
        // R2: forced 阶段返回新答案
        fakeLlm.addResponse("新答案");

        var result = agent.chatWithContextSession(
                "# Base\nbranch: branch.b0000-start\n",
                List.of(), "测试");

        assertTrue(result.success());
        assertEquals("新答案", result.finalText(),
                "finalText 应为 R2 的 currentContent，不是 R1 pendingPlainContent");
        assertEquals(1, result.toolCalls().size());
    }

    // ===== Test 3: auto-wrap 走 validation → 被拒绝 =====

    @Test
    @DisplayName("pendingPlainContent 含 [TOOL_RESULT] → auto-wrap validation 拒绝")
    void autoWrapRunsValidation() {
        // R1: 纯文本包含禁止标记 [TOOL_RESULT]
        fakeLlm.addResponse("查询结果：[TOOL_RESULT] 数据已找到 [/TOOL_RESULT]");
        // R2: forced 阶段返回空
        fakeLlm.addResponse("");

        var result = agent.chatWithContextSession(
                "# Base\nbranch: branch.b0000-start\n",
                List.of(), "查询");

        // auto-wrap 应因 validation 失败而 abort
        assertFalse(result.success(),
                "含 [TOOL_RESULT] 的内容应被 validation 拒绝");
        assertTrue(result.errorMessage() != null,
                "应有明确错误消息");
        // 不应静默接受
        assertFalse(result.finalText() != null
                        && result.finalText().contains("[TOOL_RESULT]"),
                "不应静默接受含禁止标记的 finalText");
    }

    // ===== Test 4: abort 只在 auto-wrap 失败后触发 =====

    @Test
    @DisplayName("forced 阶段先尝试 auto-wrap → 失败后才允许 abort")
    void abortOnlyAfterAutoWrapFails() {
        // R1: 正常纯文本
        fakeLlm.addResponse("正常答复内容。");
        // R2: forced 阶段返回空 → auto-wrap 用 pendingPlainContent 成功
        fakeLlm.addResponse("");

        var result = agent.chatWithContextSession(
                "# Base\nbranch: branch.b0000-start\n",
                List.of(), "测试");

        // auto-wrap 应成功，不应 abort
        assertTrue(result.success(),
                "auto-wrap 成功时不应触发 abort: " + result.errorMessage());
        assertEquals("正常答复内容。", result.finalText());

        // 验证没有经过 noToolRounds abort 路径
        assertFalse(result.errorMessage() != null
                        && result.errorMessage().contains("consecutive rounds"),
                "auto-wrap 成功时不应有 consecutive rounds 错误");
    }

    // ===== Test: R1 空 → 不触发 forcedFinishAction → 两轮无工具后 abort =====

    @Test
    @DisplayName("R1 空 content → 不触发 forcedFinishAction → consecutive no-tool abort")
    void emptyR1DoesNotTriggerForcedFinishAction() {
        // R1: 空 content — forcedFinishAction 触发条件要求 content.isBlank()==false
        fakeLlm.addResponse("");
        // R2: 仍然空 content — 不经过 auto-wrap（forcedFinishAction 仍是 false）
        fakeLlm.addResponse("");

        var result = agent.chatWithContextSession(
                "# Base\nbranch: branch.b0000-start\n",
                List.of(), "测试");

        // 由于 R1 空不触发 forcedFinishAction，两轮无工具 → consecutive abort
        assertFalse(result.success(), "两轮无内容应 abort");
        assertTrue(result.errorMessage() != null
                        && result.errorMessage().contains("consecutive"),
                "应触发 consecutive no-tool abort: " + result.errorMessage());
    }

    // ===== Test: R2 返回合法 finish_action JSON → 不经过 auto-wrap =====

    @Test
    @DisplayName("R2 forced 阶段返回合法 finish_action JSON → 直接执行，不 auto-wrap")
    void explicitFinishActionJsonBypassesAutoWrap() {
        // R1: 纯文本
        fakeLlm.addResponse("系统状态正常。");
        // R2: forced 阶段返回合法 finish_action JSON
        fakeLlm.addResponse("{\"tool\":\"finish_action\",\"args\":{\"status\":\"success\","
                + "\"message\":\"当前系统状态正常。你可以继续操作。\"}}");

        var result = agent.chatWithContextSession(
                "# Base\nbranch: branch.b0000-start\n",
                List.of(), "查看状态");

        assertTrue(result.success());
        assertEquals("当前系统状态正常。你可以继续操作。", result.finalText());
        assertEquals(1, result.toolCalls().size());
        assertEquals("finish_action", result.toolCalls().get(0).tool());
        // 验证没有经过 noToolRounds abort
        assertFalse(result.errorMessage() != null
                        && result.errorMessage().contains("consecutive"),
                "合法 finish_action JSON 路径不应触发 abort");
    }

    // ===== Test: R1="null" + R2="null" → 不 auto-wrap → abort =====

    @Test
    @DisplayName("R1=\"null\" + R2 forced=\"null\" → 都不 meaningful → NO_MEANINGFUL_CONTENT abort")
    void literalNullDoesNotAutoWrap() {
        fakeLlm.addResponse("null");
        fakeLlm.addResponse("null");

        var result = agent.chatWithContextSession(
                "# Base\nbranch: branch.b0000-start\n",
                List.of(), "测试");

        assertFalse(result.success(),
                "R1/R2 都是 \"null\" 不应 auto-wrap: " + result.finalText());
        assertFalse("null".equals(result.finalText()),
                "finalText 不应是 \"null\"");
        assertTrue(result.errorMessage() != null
                        && (result.errorMessage().contains("no meaningful content")
                        || result.errorMessage().contains("NO_MEANINGFUL")),
                "错误消息应说明无 meaningful content: " + result.errorMessage());
    }

    // ===== Test: R2="null" → fallback 到 R1 正常文本 =====

    @Test
    @DisplayName("R2 forced=\"null\" + R1=\"这是正常答案\" → fallback 到 pendingPlainContent")
    void currentNullFallsBackToPendingMeaningful() {
        fakeLlm.addResponse("这是正常答案");
        fakeLlm.addResponse("null");

        var result = agent.chatWithContextSession(
                "# Base\nbranch: branch.b0000-start\n",
                List.of(), "测试");

        assertTrue(result.success(),
                "R2=\"null\" 时应用 R1 pendingPlainContent 兜底: " + result.errorMessage());
        assertEquals("这是正常答案", result.finalText(),
                "finalText 应为 R1 pendingPlainContent");
        assertEquals(1, result.toolCalls().size());
        assertEquals("finish_action", result.toolCalls().get(0).tool());
    }

    // ===== Test: R2 有意义内容优先于 R1 =====

    @Test
    @DisplayName("R2 forced=\"新答案\" meaningful → 优先 currentContent，不用 pending")
    void currentMeaningfulPreferred() {
        fakeLlm.addResponse("旧答案");
        fakeLlm.addResponse("新答案");

        var result = agent.chatWithContextSession(
                "# Base\nbranch: branch.b0000-start\n",
                List.of(), "测试");

        assertTrue(result.success());
        assertEquals("新答案", result.finalText(),
                "R2 meaningful → finalText 应为 R2 内容");
        assertEquals(1, result.toolCalls().size());
    }

    // ===== Test: R2="NULL" (大写) → 也判无效 → fallback =====

    @Test
    @DisplayName("R2=\"NULL\" → isMeaningfulAssistantContent 判无效 → fallback")
    void nullUppercaseIsAlsoRejected() {
        fakeLlm.addResponse("有效回复");
        fakeLlm.addResponse("NULL");

        var result = agent.chatWithContextSession(
                "# Base\nbranch: branch.b0000-start\n",
                List.of(), "测试");

        assertTrue(result.success(),
                "R2=\"NULL\" 判无效 → fallback pending: " + result.errorMessage());
        assertEquals("有效回复", result.finalText());
    }

    // ===== Test: R2="undefined" → 判无效 =====

    @Test
    @DisplayName("R2=\"undefined\" → isMeaningfulAssistantContent 判无效")
    void undefinedIsRejected() {
        fakeLlm.addResponse("正常答复");
        fakeLlm.addResponse("undefined");

        var result = agent.chatWithContextSession(
                "# Base\nbranch: branch.b0000-start\n",
                List.of(), "测试");

        assertTrue(result.success());
        assertEquals("正常答复", result.finalText());
    }
}
