package com.gsim.event;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * SSE 任务事件格式测试。
 */
@DisplayName("SSE Task Events")
class SseTaskEventsTest {

    @Test
    @DisplayName("SSE 格式应正确 — event: type + data: JSON")
    void sseFormatShouldBeCorrect() throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        SseEventSink sink = new SseEventSink(out);

        sink.accept(GSimEvent.of("default", "task-001", "command_started",
                Map.of("command", "/status")));

        String result = out.toString(StandardCharsets.UTF_8);
        assertTrue(result.contains("event: command_started\n"));
        assertTrue(result.contains("data: {"));
        assertTrue(result.contains("\"sessionId\":\"default\""));
        assertTrue(result.contains("\"taskId\":\"task-001\""));
        assertTrue(result.contains("\"type\":\"command_started\""));
        assertTrue(result.endsWith("\n\n"));
    }

    @Test
    @DisplayName("SSE 应包含 sessionId 和 taskId")
    void sseShouldContainSessionAndTaskId() throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        SseEventSink sink = new SseEventSink(out);

        sink.accept(GSimEvent.of("s1", "task-abc", "result",
                Map.of("success", true)));

        String result = out.toString(StandardCharsets.UTF_8);
        assertTrue(result.contains("\"sessionId\":\"s1\""));
        assertTrue(result.contains("\"taskId\":\"task-abc\""));
        assertTrue(result.contains("\"success\":true"));
    }

    @Test
    @DisplayName("SSE 多个事件应正确分隔")
    void sseMultipleEventsShouldBeSeparated() throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        SseEventSink sink = new SseEventSink(out);

        sink.accept(GSimEvent.of("s1", "task-1", "command_started", Map.of()));
        sink.accept(GSimEvent.of("s1", "task-1", "log", Map.of("message", "ok")));
        sink.accept(GSimEvent.of("s1", "task-1", "done", Map.of()));

        String result = out.toString(StandardCharsets.UTF_8);
        String[] parts = result.split("\n\n");
        assertEquals(3, parts.length);
        assertTrue(parts[0].contains("event: command_started"));
        assertTrue(parts[1].contains("event: log"));
        assertTrue(parts[2].contains("event: done"));
    }

    @Test
    @DisplayName("事件序列应完整 — command_started → log → result → command_done → done")
    void eventSequenceShouldBeComplete() throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        SseEventSink sink = new SseEventSink(out);

        String sessionId = "s1";
        String taskId = "task-001";

        sink.accept(GSimEvent.of(sessionId, taskId, "command_started",
                Map.of("command", "/status")));
        sink.accept(GSimEvent.of(sessionId, taskId, "log",
                Map.of("message", "Running /status")));
        sink.accept(GSimEvent.of(sessionId, taskId, "result",
                Map.of("success", true)));
        sink.accept(GSimEvent.of(sessionId, taskId, "command_done", Map.of()));
        sink.accept(GSimEvent.of(sessionId, taskId, "done", Map.of()));

        String result = out.toString(StandardCharsets.UTF_8);
        assertTrue(result.contains("event: command_started"));
        assertTrue(result.contains("event: log"));
        assertTrue(result.contains("event: result"));
        assertTrue(result.contains("event: command_done"));
        assertTrue(result.contains("event: done"));
    }

    @Test
    @DisplayName("关闭后不应再输出")
    void shouldNotOutputAfterClose() throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        SseEventSink sink = new SseEventSink(out);

        sink.accept(GSimEvent.of("s1", "task-1", "event1", Map.of()));
        sink.close();

        int before = out.toByteArray().length;
        sink.accept(GSimEvent.of("s1", "task-1", "event2", Map.of()));
        int after = out.toByteArray().length;

        assertEquals(before, after);
    }

    @Test
    @DisplayName("toSseJson 应正确序列化")
    void toSseJsonShouldSerializeCorrectly() {
        var event = GSimEvent.of("s1", "task-1", "my_type",
                Map.of("key1", "value1", "key2", 42));

        String json = SseEventSink.toSseJson(event);

        assertTrue(json.contains("\"sessionId\":\"s1\""));
        assertTrue(json.contains("\"taskId\":\"task-1\""));
        assertTrue(json.contains("\"type\":\"my_type\""));
        assertTrue(json.contains("\"key1\":\"value1\""));
        assertTrue(json.contains("\"key2\":42"));
    }

    @Test
    @DisplayName("null taskId 不应出现在 JSON 中")
    void nullTaskIdShouldNotBeInJson() {
        var event = GSimEvent.of("s1", "event_type", Map.of());

        String json = SseEventSink.toSseJson(event);

        assertTrue(json.contains("\"sessionId\":\"s1\""));
        assertFalse(json.contains("taskId"));
    }
}
