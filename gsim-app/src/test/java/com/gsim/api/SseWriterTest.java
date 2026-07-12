package com.gsim.api;

import com.gsim.event.GSimEvent;
import com.gsim.event.SseEventSink;
import com.gsim.util.JsonUtils;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * SSE 输出测试 — 验证 SSE 格式和 SseEventSink。
 */
@DisplayName("SSE Output")
class SseWriterTest {

    @Test
    @DisplayName("SseEventSink 输出正确 SSE 格式")
    void shouldOutputSseFormat() {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        SseEventSink sink = new SseEventSink(baos);

        GSimEvent event = GSimEvent.of("default", "task-001", "command_started",
                Map.of("command", "/status"));
        sink.accept(event);

        String output = baos.toString(StandardCharsets.UTF_8);
        assertTrue(output.startsWith("event: command_started\n"));
        assertTrue(output.contains("data: "));
        assertTrue(output.contains("sessionId"));
        assertTrue(output.contains("default"));
        assertTrue(output.endsWith("\n\n"));
    }

    @Test
    @DisplayName("SseEventSink 事件包含 taskId")
    void shouldIncludeTaskId() {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        SseEventSink sink = new SseEventSink(baos);

        GSimEvent event = GSimEvent.of("default", "task-123", "llm_delta",
                Map.of("text", "hello"));
        sink.accept(event);

        String output = baos.toString(StandardCharsets.UTF_8);
        assertTrue(output.contains("taskId"));
        assertTrue(output.contains("task-123"));
    }

    @Test
    @DisplayName("SseEventSink toSseJson 应包含必要字段")
    void shouldContainRequiredFields() {
        GSimEvent event = GSimEvent.of("s1", "t1", "run_stage",
                Map.of("stage", "analyze", "message", "analyzing..."));

        String json = SseEventSink.toSseJson(event);
        Map<?, ?> parsed = JsonUtils.fromJson(json, Map.class);

        assertEquals("s1", parsed.get("sessionId"));
        assertEquals("t1", parsed.get("taskId"));
        assertEquals("run_stage", parsed.get("type"));
        assertEquals("analyze", parsed.get("stage"));
        assertEquals("analyzing...", parsed.get("message"));
    }

    @Test
    @DisplayName("SseEventSink toSseJson 不含 taskId 时不应有 null")
    void shouldOmitNullTaskId() {
        GSimEvent event = GSimEvent.of("s1", "done", Map.of());
        String json = SseEventSink.toSseJson(event);
        Map<?, ?> parsed = JsonUtils.fromJson(json, Map.class);
        assertNull(parsed.get("taskId"));
        assertEquals("s1", parsed.get("sessionId"));
    }

    @Test
    @DisplayName("关闭后不应再输出")
    void shouldNotOutputAfterClose() {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        SseEventSink sink = new SseEventSink(baos);

        GSimEvent event = GSimEvent.of("s", "type");
        sink.accept(event);
        int lenAfterFirst = baos.size();

        sink.close();
        sink.accept(event);  // 应该被忽略
        assertEquals(lenAfterFirst, baos.size());
    }
}
