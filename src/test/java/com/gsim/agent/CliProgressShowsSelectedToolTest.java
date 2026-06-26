package com.gsim.agent;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 验证 CliAgentProgressSink.format 在 quiet 模式下的行为：
 * 常规工具进度事件 → null（静默），错误事件 → 非 null。
 */
@DisplayName("CLI 进度显示（quiet mode）")
class CliProgressShowsSelectedToolTest {

    @Test
    @DisplayName("TOOL_SELECTED → null（静默）")
    void toolSelectedIsQuiet() {
        var event = new AgentProgressEvent(AgentProgressEvent.TOOL_SELECTED,
                1, 5, "LLM 选择工具：player_action_list",
                Map.of("tool", "player_action_list"));
        assertNull(CliAgentProgressSink.format(event));
    }

    @Test
    @DisplayName("TOOL_EXECUTING → null（静默）")
    void toolExecutingIsQuiet() {
        var event = new AgentProgressEvent(AgentProgressEvent.TOOL_EXECUTING,
                1, 5, "正在执行工具：player_action_list",
                Map.of("tool", "player_action_list"));
        assertNull(CliAgentProgressSink.format(event));
    }

    @Test
    @DisplayName("TOOL_SUCCESS → null（静默）")
    void toolSuccessIsQuiet() {
        var event = new AgentProgressEvent(AgentProgressEvent.TOOL_SUCCESS,
                1, 5, "工具成功：player_action_list",
                Map.of("tool", "player_action_list"));
        assertNull(CliAgentProgressSink.format(event));
    }

    @Test
    @DisplayName("TOOL_FAILED → 非 null（错误可见）")
    void toolFailedIsVisible() {
        var event = new AgentProgressEvent(AgentProgressEvent.TOOL_FAILED,
                1, 5, "工具失败：bad_tool",
                Map.of("tool", "bad_tool", "error", "not found"));
        String line = CliAgentProgressSink.format(event);
        assertNotNull(line);
        assertTrue(line.contains("bad_tool"));
        assertTrue(line.contains("not found"));
    }

    @Test
    @DisplayName("FINISH_ACTION_ACCEPTED → null（不额外输出）")
    void finishAcceptedOutputsNull() {
        var event = AgentProgressEvent.finishAccepted(1, 5);
        assertNull(CliAgentProgressSink.format(event));
    }
}
