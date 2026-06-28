package com.gsim.agent;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 验证 CliAgentProgressSink.format 对工具进度事件的渲染：
 * 工具选择/执行/成功均可见（终端反馈），静默事件保持静默。
 */
@DisplayName("CLI 工具进度事件显示")
class CliProgressShowsSelectedToolTest {

    @Test
    @DisplayName("TOOL_SELECTED → 可见（工具名 + 参数）")
    void toolSelectedIsVisible() {
        var event = new AgentProgressEvent(AgentProgressEvent.TOOL_SELECTED,
                1, 5, "LLM 选择工具：player_action_list",
                Map.of("tool", "player_action_list", "paramsSummary", ""));
        String line = CliAgentProgressSink.format(event);
        assertNotNull(line, "TOOL_SELECTED should be visible");
        assertTrue(line.contains("player_action_list"),
                "Should include tool name: " + line);
        assertTrue(line.contains("🔧"),
                "Should have wrench emoji: " + line);
    }

    @Test
    @DisplayName("TOOL_EXECUTING → 可见")
    void toolExecutingIsVisible() {
        var event = new AgentProgressEvent(AgentProgressEvent.TOOL_EXECUTING,
                1, 5, "正在执行工具：player_action_list",
                Map.of("tool", "player_action_list"));
        String line = CliAgentProgressSink.format(event);
        assertNotNull(line, "TOOL_EXECUTING should be visible");
        assertTrue(line.contains("player_action_list"),
                "Should include tool name: " + line);
        assertTrue(line.contains("执行中"),
                "Should show executing status: " + line);
    }

    @Test
    @DisplayName("TOOL_SUCCESS → 可见")
    void toolSuccessIsVisible() {
        var event = new AgentProgressEvent(AgentProgressEvent.TOOL_SUCCESS,
                1, 5, "工具成功：player_action_list",
                Map.of("tool", "player_action_list", "resultSummary", "3 items"));
        String line = CliAgentProgressSink.format(event);
        assertNotNull(line, "TOOL_SUCCESS should be visible");
        assertTrue(line.contains("player_action_list"),
                "Should include tool name: " + line);
        assertTrue(line.contains("✅"),
                "Should have check emoji: " + line);
    }

    @Test
    @DisplayName("TOOL_FAILED → 可见（错误）")
    void toolFailedIsVisible() {
        var event = new AgentProgressEvent(AgentProgressEvent.TOOL_FAILED,
                1, 5, "工具失败：bad_tool",
                Map.of("tool", "bad_tool", "error", "not found"));
        String line = CliAgentProgressSink.format(event);
        assertNotNull(line);
        assertTrue(line.contains("bad_tool"));
        assertTrue(line.contains("not found"));
        assertTrue(line.contains("❌"),
                "Should have cross emoji: " + line);
    }

    @Test
    @DisplayName("FINISH_ACTION_ACCEPTED → null（不额外输出）")
    void finishAcceptedOutputsNull() {
        var event = AgentProgressEvent.finishAccepted(1, 5);
        assertNull(CliAgentProgressSink.format(event));
    }
}
