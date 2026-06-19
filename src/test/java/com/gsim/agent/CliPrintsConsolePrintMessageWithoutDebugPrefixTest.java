package com.gsim.agent;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 验证 CLI progress sink 对 AGENT_PUBLIC_MESSAGE 直接输出正文，不加调试前缀。
 */
@DisplayName("CLI 对 console_print 消息不加调试前缀")
class CliPrintsConsolePrintMessageWithoutDebugPrefixTest {

    @Test
    @DisplayName("AGENT_PUBLIC_MESSAGE 直接输出正文")
    void agentPublicMessageDirectOutput() {
        String tableContent = """
                # 报名表

                | 字段 | 值 |
                |------|-----|
                | 姓名 | |
                | 种族 | |
                """;
        var event = AgentProgressEvent.publicMessage(tableContent);

        String formatted = CliAgentProgressSink.format(event);

        // 不应包含 [Agent] 前缀
        assertFalse(formatted.contains("[Agent]"),
                "Should NOT contain [Agent] debug prefix: " + formatted);
        // 应包含正文
        assertTrue(formatted.contains("报名表"),
                "Should contain the actual message content: " + formatted);
        assertTrue(formatted.contains("| 姓名 |"),
                "Should contain the table content: " + formatted);
    }

    @Test
    @DisplayName("AGENT_PUBLIC_MESSAGE 完整保留可复制表格")
    void preservesCopyableTableContent() {
        String longMessage = "以下是完整的模板：\n\n```\n字段1：____\n字段2：____\n```";
        var event = AgentProgressEvent.publicMessage(longMessage);

        String formatted = CliAgentProgressSink.format(event);

        assertEquals(longMessage, formatted,
                "The output should be exactly the message text, unmodified");
    }

    @Test
    @DisplayName("其他事件仍带 [Agent] 前缀")
    void otherEventsStillHaveAgentPrefix() {
        var waitingEvent = AgentProgressEvent.waitingLlm(1, 5);
        String formatted = CliAgentProgressSink.format(waitingEvent);
        assertTrue(formatted.contains("[Agent]"),
                "Other events should retain [Agent] prefix: " + formatted);
    }
}
