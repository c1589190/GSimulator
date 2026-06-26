package com.gsim.agent;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 验证 CliAgentProgressSink.format 生成的进度行简短且信息完整。
 */
@DisplayName("CLI 进度显示工具选择/执行")
class CliProgressShowsSelectedToolTest {

    @Test
    @DisplayName("TOOL_SELECTED 格式包含工具名")
    void toolSelectedShowsToolName() {
        var event = new AgentProgressEvent(AgentProgressEvent.TOOL_SELECTED,
                1, 5, "LLM 选择工具：player_action_list",
                Map.of("tool", "player_action_list"));
        String line = CliAgentProgressSink.format(event);
        assertNotNull(line);
        assertTrue(line.contains("player_action_list"),
                "Should contain tool name: " + line);
        assertTrue(line.contains("[Agent]"),
                "Should start with [Agent] prefix: " + line);
    }

    @Test
    @DisplayName("TOOL_EXECUTING 格式包含工具名")
    void toolExecutingShowsToolName() {
        var event = new AgentProgressEvent(AgentProgressEvent.TOOL_EXECUTING,
                1, 5, "正在执行工具：player_action_list",
                Map.of("tool", "player_action_list"));
        String line = CliAgentProgressSink.format(event);
        assertNotNull(line);
        assertTrue(line.contains("player_action_list"));
        assertTrue(line.contains("[Agent]"));
    }

    @Test
    @DisplayName("TOOL_SUCCESS 格式包含工具名")
    void toolSuccessShowsToolName() {
        var event = new AgentProgressEvent(AgentProgressEvent.TOOL_SUCCESS,
                1, 5, "工具成功：player_action_list",
                Map.of("tool", "player_action_list"));
        String line = CliAgentProgressSink.format(event);
        assertNotNull(line);
        assertTrue(line.contains("player_action_list"));
    }

    @Test
    @DisplayName("输出行长度 ≤ 120 chars")
    void outputLineWithinReasonableLength() {
        var event = AgentProgressEvent.toolSelected(1, 5, "query_keyword");
        String line = CliAgentProgressSink.format(event);
        assertNotNull(line);
        assertTrue(line.length() <= 120,
                "Line should be ≤ 120 chars, got " + line.length() + ": " + line);
    }

    @Test
    @DisplayName("FINISH_ACTION_ACCEPTED 输出 null（不额外输出）")
    void finishAcceptedOutputsNull() {
        var event = AgentProgressEvent.finishAccepted(1, 5);
        String line = CliAgentProgressSink.format(event);
        assertNull(line, "FINISH_ACTION_ACCEPTED should produce no output");
    }
}
