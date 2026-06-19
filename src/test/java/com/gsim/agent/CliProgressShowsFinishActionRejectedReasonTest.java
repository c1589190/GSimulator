package com.gsim.agent;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 验证 CLI 进度显示 finish_action 拒绝原因。
 */
@DisplayName("CLI 进度显示 finish_action 拒绝原因")
class CliProgressShowsFinishActionRejectedReasonTest {

    @Test
    @DisplayName("FINISH_ACTION_REJECTED 格式包含人类可读拒绝原因")
    void finishRejectedShowsReason() {
        var event = AgentProgressEvent.finishRejected(2, 5,
                "CLAIM_WITHOUT_SUPPORTING_TOOL_RESULT");
        String line = CliAgentProgressSink.format(event);
        assertNotNull(line);
        assertTrue(line.contains("finish_action 被拒绝"),
                "Should mention finish_action rejected: " + line);
        assertTrue(line.contains("声称了未经真实工具执行支持的结果"),
                "Should include human-readable reject reason: " + line);
        assertFalse(line.contains("CLAIM_WITHOUT_SUPPORTING_TOOL_RESULT"),
                "Should NOT leak raw reason code to CLI: " + line);
        assertTrue(line.contains("[Agent]"),
                "Should start with [Agent] prefix: " + line);
    }

    @Test
    @DisplayName("FINISH_ACTION_WITH_OTHER_TOOLS → 中文文案")
    void mixedToolsShowsChineseText() {
        var event = AgentProgressEvent.finishRejected(1, 5,
                "FINISH_ACTION_WITH_OTHER_TOOLS");
        String line = CliAgentProgressSink.format(event);
        assertNotNull(line);
        assertTrue(line.contains("与其他工具同轮混用，要求模型先单独执行工具"),
                "Should show Chinese text for mixed tools: " + line);
        assertFalse(line.contains("FINISH_ACTION_WITH_OTHER_TOOLS"),
                "Should NOT leak raw reason code: " + line);
    }

    @Test
    @DisplayName("未知 reason code 直接展示原文")
    void unknownReasonShowsRawText() {
        var event = AgentProgressEvent.finishRejected(2, 5,
                "finish_action 消息不能包含 [工具结果] 占位符。请根据实际工具返回信息撰写回复。");
        String line = CliAgentProgressSink.format(event);
        assertNotNull(line);
        // message validation 错误原文直接展示
        assertTrue(line.contains("[工具结果]"),
                "Should show raw validation message: " + line);
        assertTrue(line.startsWith("[Agent] finish_action 被拒绝："),
                "Should have correct prefix: " + line);
    }

    @Test
    @DisplayName("FINISH_ACTION_REJECTED 长度 ≤ 120 chars")
    void finishRejectedLineWithinLength() {
        var event = AgentProgressEvent.finishRejected(3, 5,
                "MESSAGE_BANNED_CONTENT_PLACEHOLDER");
        String line = CliAgentProgressSink.format(event);
        assertNotNull(line);
        assertTrue(line.length() <= 120,
                "Line should be ≤ 120 chars, got " + line.length());
    }

    @Test
    @DisplayName("Null rejectReason 不崩溃")
    void nullRejectReasonDoesNotCrash() {
        var event = new AgentProgressEvent(AgentProgressEvent.FINISH_ACTION_REJECTED,
                1, 5, "finish_action 被拒绝：null，正在要求模型重写最终回复。",
                Map.of("rejectReason", ""));
        String line = CliAgentProgressSink.format(event);
        assertNotNull(line);
    }
}
