package com.gsim.event;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * ConsoleEventSink 测试 — 验证 CLI 事件格式化输出。
 */
@DisplayName("ConsoleEventSink")
class ConsoleEventSinkTest {

    private StringWriter sw;
    private PrintWriter pw;
    private ConsoleEventSink sink;

    @BeforeEach
    void setUp() {
        sw = new StringWriter();
        pw = new PrintWriter(sw, true);
        sink = new ConsoleEventSink(pw);
    }

    @Test
    @DisplayName("command_started 事件输出")
    void shouldDisplayCommandStarted() {
        sink.accept(GSimEvent.of("default", "command_started",
                Map.of("command", "/status")));
        String output = sw.toString();
        assertTrue(output.contains("[>]"));
        assertTrue(output.contains("/status"));
    }

    @Test
    @DisplayName("command_done 和 command_error 事件输出")
    void shouldDisplayCommandDoneAndError() {
        sink.accept(GSimEvent.of("default", "command_done", Map.of()));
        assertTrue(sw.toString().contains("[✓]"));

        sw.getBuffer().setLength(0);
        sink.accept(GSimEvent.of("default", "command_error",
                Map.of("error", "something wrong")));
        assertTrue(sw.toString().contains("[✗]"));
        assertTrue(sw.toString().contains("something wrong"));
    }

    @Test
    @DisplayName("llm_delta 直接流式打印")
    void shouldStreamLlmDelta() {
        sink.accept(GSimEvent.of("default", "llm_delta",
                Map.of("text", "Hello")));
        sink.accept(GSimEvent.of("default", "llm_delta",
                Map.of("text", " world")));
        String output = sw.toString();
        assertTrue(output.contains("Hello"));
        assertTrue(output.contains(" world"));
    }

    @Test
    @DisplayName("llm_reasoning_delta 以 [reasoning] 前缀打印")
    void shouldPrefixReasoningDelta() {
        sink.accept(GSimEvent.of("default", "llm_reasoning_delta",
                Map.of("text", "thinking step 1...")));
        String output = sw.toString();
        assertTrue(output.contains("[reasoning]"));
        assertTrue(output.contains("thinking step 1..."));
    }

    @Test
    @DisplayName("llm_delta 应在 reasoning 后换行")
    void shouldBreakAfterReasoning() {
        sink.accept(GSimEvent.of("default", "llm_reasoning_delta",
                Map.of("text", "think...")));
        sink.accept(GSimEvent.of("default", "llm_delta",
                Map.of("text", "output")));
        // reasoningOpen 标志应在 llm_delta 时被重置，输出前应有换行
        String output = sw.toString();
        assertTrue(output.contains("[reasoning]"));
        assertTrue(output.contains("output"));
    }

    @Test
    @DisplayName("tool_started 和 tool_done 事件输出")
    void shouldDisplayToolEvents() {
        sink.accept(GSimEvent.of("default", "tool_started",
                Map.of("tool", "searchdb", "message", "正在查询")));
        sink.accept(GSimEvent.of("default", "tool_done",
                Map.of("tool", "searchdb", "count", 5)));
        String output = sw.toString();
        assertTrue(output.contains("[🔧]"));
        assertTrue(output.contains("searchdb"));
        assertTrue(output.contains("5"));
    }

    @Test
    @DisplayName("run_stage 事件输出")
    void shouldDisplayRunStage() {
        sink.accept(GSimEvent.of("default", "run_stage",
                Map.of("stage", "analyze_actions", "message", "正在分析")));
        String output = sw.toString();
        assertTrue(output.contains("[…]"));
        assertTrue(output.contains("analyze_actions"));
        assertTrue(output.contains("正在分析"));
    }

    @Test
    @DisplayName("import_progress 事件输出")
    void shouldDisplayImportProgress() {
        sink.accept(GSimEvent.of("default", "import_progress",
                Map.of("message", "Starting import...")));
        String output = sw.toString();
        assertTrue(output.contains("[📥]"));
        assertTrue(output.contains("Starting import..."));
    }
}
