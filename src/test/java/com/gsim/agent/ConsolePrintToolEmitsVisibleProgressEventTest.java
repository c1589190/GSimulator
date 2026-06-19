package com.gsim.agent;

import com.gsim.agent.tool.ConsolePrintTool;
import com.gsim.tool.ToolCall;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 验证 ConsolePrintTool 调用 progressSink.onProgress 发送 AGENT_PUBLIC_MESSAGE 事件。
 */
@DisplayName("ConsolePrintTool 发送可见进度事件")
class ConsolePrintToolEmitsVisibleProgressEventTest {

    @Test
    @DisplayName("调用 console_print 后 progressSink 收到 AGENT_PUBLIC_MESSAGE")
    void emitsAgentPublicMessageEvent() {
        var capturedEvents = new ArrayList<AgentProgressEvent>();
        AgentProgressSink sink = capturedEvents::add;

        var tool = new ConsolePrintTool(sink);
        var result = tool.execute(new ToolCall("console_print",
                Map.of("message", "这是给用户看的报名表模板")));

        assertTrue(result.success(), "Tool should succeed: " + result.error());

        // 验证 event 发出
        assertEquals(1, capturedEvents.size(), "Should emit one progress event");
        AgentProgressEvent event = capturedEvents.get(0);
        assertEquals(AgentProgressEvent.AGENT_PUBLIC_MESSAGE, event.phase(),
                "Event phase should be AGENT_PUBLIC_MESSAGE");
        assertEquals("这是给用户看的报名表模板", event.detail(),
                "Event detail should be the message text");
        assertEquals("console_print", event.meta().get("source"),
                "Meta should have source=console_print");
        assertEquals(String.valueOf("这是给用户看的报名表模板".length()),
                event.meta().get("chars"), "Meta should have chars count");
    }

    @Test
    @DisplayName("空 message 应失败")
    void emptyMessageFails() {
        var capturedEvents = new ArrayList<AgentProgressEvent>();
        AgentProgressSink sink = capturedEvents::add;

        var tool = new ConsolePrintTool(sink);
        var result = tool.execute(new ToolCall("console_print", Map.of("message", "")));

        assertFalse(result.success(), "Empty message should fail");
        assertTrue(capturedEvents.isEmpty(), "No event should be emitted on failure");
    }

    @Test
    @DisplayName("包含敏感内容的 message 应被拒绝")
    void sensitiveContentRejected() {
        var capturedEvents = new ArrayList<AgentProgressEvent>();
        AgentProgressSink sink = capturedEvents::add;
        var tool = new ConsolePrintTool(sink);

        // TOOL_RESULT 标记
        var r1 = tool.execute(new ToolCall("console_print",
                Map.of("message", "结果：[TOOL_RESULT]123")));
        assertFalse(r1.success(), "TOOL_RESULT should be rejected");

        // API Key
        var r2 = tool.execute(new ToolCall("console_print",
                Map.of("message", "Authorization: Bearer xyz")));
        assertFalse(r2.success(), "Authorization should be rejected");

        // api_key
        var r3 = tool.execute(new ToolCall("console_print",
                Map.of("message", "api_key=sk-test")));
        assertFalse(r3.success(), "api_key should be rejected");

        assertTrue(capturedEvents.isEmpty(), "No events for rejected messages");
    }

    @Test
    @DisplayName("null progressSink 不抛异常")
    void nullProgressSinkDoesNotThrow() {
        var tool = new ConsolePrintTool(null);
        var result = tool.execute(new ToolCall("console_print",
                Map.of("message", "hello")));
        assertTrue(result.success(), "Should still succeed with null sink");
    }
}
