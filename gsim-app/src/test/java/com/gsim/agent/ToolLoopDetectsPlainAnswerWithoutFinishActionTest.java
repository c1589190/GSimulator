package com.gsim.agent;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 验证 finish intent detection 正确识别「模型想结束但没合法 finish_action」。
 */
@DisplayName("ToolLoop 检测 plain answer without finish_action")
class ToolLoopDetectsPlainAnswerWithoutFinishActionTest {

    @Test
    @DisplayName("无 tool call + cleanedDraft 非空 + 非 invalid → PLAIN_ANSWER_WITHOUT_FINISH")
    void plainAnswerDetected() {
        var result = ToolLoopDebug.detectFinishIntent(
                0, 0, false, "当前系统状态正常，你可以继续操作。", false);
        assertEquals(ToolLoopDebug.FinishIntent.PLAIN_ANSWER_WITHOUT_FINISH, result);
    }

    @Test
    @DisplayName("有 API tool_call → NONE（正常走工具路径）")
    void apiToolCallReturnsNone() {
        var result = ToolLoopDebug.detectFinishIntent(
                1, 0, false, "调用工具中...", false);
        assertEquals(ToolLoopDebug.FinishIntent.NONE, result);
    }

    @Test
    @DisplayName("有 text fallback tool → NONE")
    void textFallbackToolReturnsNone() {
        var result = ToolLoopDebug.detectFinishIntent(
                0, 1, false, "{\"tool\":\"echo\"}", false);
        assertEquals(ToolLoopDebug.FinishIntent.NONE, result);
    }

    @Test
    @DisplayName("invalidToolIntent=true → INVALID_BRACKET_TOOL_INTENT")
    void invalidBracketIntentDetected() {
        var result = ToolLoopDebug.detectFinishIntent(
                0, 0, true, "[调用 player_action_list] {...}", false);
        assertEquals(ToolLoopDebug.FinishIntent.INVALID_BRACKET_TOOL_INTENT, result);
    }

    @Test
    @DisplayName("finishActionRejected=true → FINISH_ACTION_REJECTED（优先级最高）")
    void finishRejectedTakesPriority() {
        // 即使同时有工具调用，finish rejected 仍优先
        var result = ToolLoopDebug.detectFinishIntent(
                1, 0, false, "完成后显示...", true);
        assertEquals(ToolLoopDebug.FinishIntent.FINISH_ACTION_REJECTED, result);
    }

    @Test
    @DisplayName("cleanedDraft 为空 → NONE（无实质内容不算 plain answer）")
    void emptyDraftReturnsNone() {
        var result = ToolLoopDebug.detectFinishIntent(
                0, 0, false, "", false);
        assertEquals(ToolLoopDebug.FinishIntent.NONE, result);
    }

    @Test
    @DisplayName("cleanedDraft 为 null → NONE")
    void nullDraftReturnsNone() {
        var result = ToolLoopDebug.detectFinishIntent(
                0, 0, false, null, false);
        assertEquals(ToolLoopDebug.FinishIntent.NONE, result);
    }
}
