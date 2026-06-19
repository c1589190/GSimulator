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
    @DisplayName("FINISH_ACTION_REJECTED 格式包含 rejectReason")
    void finishRejectedShowsReason() {
        var event = AgentProgressEvent.finishRejected(2, 5,
                "CLAIM_WITHOUT_SUPPORTING_TOOL_RESULT");
        String line = CliAgentProgressSink.format(event);
        assertNotNull(line);
        assertTrue(line.contains("finish_action 被拒绝"),
                "Should mention finish_action rejected: " + line);
        assertTrue(line.contains("CLAIM_WITHOUT_SUPPORTING_TOOL_RESULT"),
                "Should include reject reason: " + line);
        assertTrue(line.contains("[Agent]"),
                "Should start with [Agent] prefix: " + line);
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
