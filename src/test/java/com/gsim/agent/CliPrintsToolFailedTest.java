package com.gsim.agent;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 验证 CliAgentProgressSink.format() 对 TOOL_FAILED 事件的渲染。
 */
@DisplayName("CLI 渲染 TOOL_FAILED 事件")
class CliPrintsToolFailedTest {

    @Test
    @DisplayName("TOOL_FAILED 输出工具名和失败原因")
    void toolFailedShowsNameAndReason() {
        var event = new AgentProgressEvent(
                AgentProgressEvent.TOOL_FAILED, 2, 8, "工具失败：write_element",
                Map.of("tool", "write_element", "error", "title 不能为空"));

        String formatted = CliAgentProgressSink.format(event);

        assertNotNull(formatted);
        assertTrue(formatted.contains("❌"),
                "Should have ❌ marker: " + formatted);
        assertTrue(formatted.contains("write_element"),
                "Should include tool name: " + formatted);
        assertTrue(formatted.contains("title 不能为空"),
                "Should include error reason: " + formatted);
    }

    @Test
    @DisplayName("TOOL_FAILED 无 error 时不追加原因")
    void toolFailedWithoutErrorOmitsReason() {
        var event = new AgentProgressEvent(
                AgentProgressEvent.TOOL_FAILED, 1, 5, "工具失败：echo",
                Map.of("tool", "echo", "error", ""));

        String formatted = CliAgentProgressSink.format(event);

        assertNotNull(formatted);
        assertTrue(formatted.contains("echo"),
                "Should include tool name: " + formatted);
        assertFalse(formatted.contains(" — "),
                "Should NOT append reason when error is blank: " + formatted);
    }

    @Test
    @DisplayName("TOOL_FAILED 使用 factory 方法构造")
    void toolFailedViaFactory() {
        var event = AgentProgressEvent.toolFailed(3, 10,
                "branch_switch", "branch not found");

        String formatted = CliAgentProgressSink.format(event);

        assertNotNull(formatted);
        assertTrue(formatted.contains("❌"),
                "Should have ❌ marker: " + formatted);
        assertTrue(formatted.contains("branch_switch"),
                "Should include tool name: " + formatted);
        assertTrue(formatted.contains("branch not found"),
                "Should include error reason: " + formatted);
    }

    @Test
    @DisplayName("TOOL_FAILED 带 null error 安全处理")
    void toolFailedWithNullError() {
        var event = new AgentProgressEvent(
                AgentProgressEvent.TOOL_FAILED, 1, 5, "工具失败：x",
                Map.of("tool", "x"));

        String formatted = CliAgentProgressSink.format(event);

        assertNotNull(formatted);
        assertTrue(formatted.contains("x"),
                "Should include tool name: " + formatted);
        assertFalse(formatted.contains(" — "),
                "null/absent error should not show reason");
    }
}
