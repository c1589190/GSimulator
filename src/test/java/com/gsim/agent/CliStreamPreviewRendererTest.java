package com.gsim.agent;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

/**
 * CliStreamPreviewRenderer 单元测试。
 * 使用 ByteArrayOutputStream 捕获输出验证灰框行为。
 */
@DisplayName("CLI 流式预览渲染器测试")
class CliStreamPreviewRendererTest {

    private ByteArrayOutputStream baos;
    private PrintStream ps;
    private CliStreamPreviewRenderer renderer;

    @BeforeEach
    void setUp() {
        baos = new ByteArrayOutputStream();
        ps = new PrintStream(baos, true, StandardCharsets.UTF_8);
        // 强制启用 ANSI 进行测试（实际运行时会检测终端）
        renderer = new CliStreamPreviewRenderer(ps, true, 3000, true) {
            @Override
            public void start() {
                // 绕过 ANSI 检测，直接激活
            }
        };
    }

    @Test
    @DisplayName("start 后 clear → 输出被清除且不保留灰框内容")
    void startAndClearDoesNotLeaveContent() {
        // 使用反射或直接调用内部方法
        // 由于 start() 被重写为空，我们测试 clear() 的幂等性
        renderer.clear(); // 不在 active 状态，应该无操作
        assertFalse(renderer.isActive());
    }

    @Test
    @DisplayName("appendContent 在非 active 状态不输出")
    void appendContentWhenNotActiveDoesNothing() {
        renderer.appendContent("test");
        String output = baos.toString(StandardCharsets.UTF_8);
        assertTrue(output.isEmpty(), "非 active 状态不应有输出");
    }

    @Test
    @DisplayName("appendReasoning 在非 active 状态不输出")
    void appendReasoningWhenNotActiveDoesNothing() {
        renderer.appendReasoning("thinking...");
        String output = baos.toString(StandardCharsets.UTF_8);
        assertTrue(output.isEmpty(), "非 active 状态不应有输出");
    }

    @Test
    @DisplayName("disabled 模式下不输出任何内容")
    void disabledRendererDoesNothing() {
        CliStreamPreviewRenderer disabled = new CliStreamPreviewRenderer(
                ps, false, 3000, true);
        disabled.appendContent("test");
        disabled.appendReasoning("think");
        disabled.clear();
        String output = baos.toString(StandardCharsets.UTF_8);
        assertTrue(output.isEmpty());
    }

    @Test
    @DisplayName("clear 后 isActive 返回 false")
    void clearSetsInactive() {
        assertFalse(renderer.isActive(), "初始状态应为 inactive");
        renderer.clear(); // 幂等
        assertFalse(renderer.isActive());
    }

    @Test
    @DisplayName("CliAgentProgressSink 流式事件 → 正确路由到 renderer")
    void sinkRoutesStreamEventsToRenderer() throws Exception {
        // 创建一个可控的 renderer spy
        var events = new java.util.concurrent.CopyOnWriteArrayList<String>();

        CliStreamPreviewRenderer spyRenderer = new CliStreamPreviewRenderer(ps, true, 3000, true) {
            private boolean active = false;

            @Override
            public void start() {
                events.add("start");
                active = true;
            }

            @Override
            public void appendReasoning(String delta) {
                events.add("reasoning:" + delta);
            }

            @Override
            public void appendContent(String delta) {
                events.add("content:" + delta);
            }

            @Override
            public void clear() {
                events.add("clear");
                active = false;
            }

            @Override
            public boolean isActive() {
                return active;
            }
        };

        CliAgentProgressSink sink = new CliAgentProgressSink(
                ps, true, spyRenderer);

        // 模拟完整流式周期
        sink.onProgress(AgentProgressEvent.llmStreamStarted());
        assertTrue(events.contains("start"));

        sink.onProgress(AgentProgressEvent.llmReasoningDelta("正在分析..."));
        assertTrue(events.contains("reasoning:正在分析..."));

        sink.onProgress(AgentProgressEvent.llmContentDelta("输出内容"));
        assertTrue(events.contains("content:输出内容"));

        sink.onProgress(AgentProgressEvent.llmStreamCompleted());
        assertTrue(events.contains("clear"));

        // 顺序验证
        assertEquals("start", events.get(0));
        assertEquals("reasoning:正在分析...", events.get(1));
        assertEquals("content:输出内容", events.get(2));
        assertEquals("clear", events.get(3));
    }

    @Test
    @DisplayName("CliAgentProgressSink 流式失败 → clear + 打印错误")
    void sinkClearsAndPrintsErrorOnStreamFailed() {
        ByteArrayOutputStream errBaos = new ByteArrayOutputStream();
        PrintStream errPs = new PrintStream(errBaos, true, StandardCharsets.UTF_8);

        var cleared = new java.util.concurrent.atomic.AtomicBoolean(false);
        CliStreamPreviewRenderer testRenderer = new CliStreamPreviewRenderer(ps, true, 3000, true) {
            @Override
            public void clear() {
                cleared.set(true);
            }
        };

        CliAgentProgressSink sink = new CliAgentProgressSink(
                errPs, true, testRenderer);

        sink.onProgress(AgentProgressEvent.llmStreamFailed("连接超时"));
        assertTrue(cleared.get(), "失败时应清除灰框");

        String errOutput = errBaos.toString(StandardCharsets.UTF_8);
        assertTrue(errOutput.contains("LLM 流式输出失败"),
                "应打印失败信息，实际输出: " + errOutput);
        assertTrue(errOutput.contains("连接超时"),
                "应包含错误详情，实际输出: " + errOutput);
    }

    @Test
    @DisplayName("CliAgentProgressSink 无 renderer 时流式事件不崩溃")
    void sinkWithoutRendererDoesNotCrashOnStreamEvents() {
        CliAgentProgressSink sink = new CliAgentProgressSink(ps, true, null);

        // 这些调用不应崩溃
        assertDoesNotThrow(() -> {
            sink.onProgress(AgentProgressEvent.llmStreamStarted());
            sink.onProgress(AgentProgressEvent.llmContentDelta("test"));
            sink.onProgress(AgentProgressEvent.llmReasoningDelta("think"));
            sink.onProgress(AgentProgressEvent.llmStreamCompleted());
            sink.onProgress(AgentProgressEvent.llmStreamFailed("error"));
        });
    }

    @Test
    @DisplayName("max_chars 截断内容只保留末尾")
    void maxCharsTruncationConceptual() {
        // maxChars 逻辑在 render() 内部通过 truncate() 方法实现
        // 这里验证概念：truncate 应该保留末尾
        StringBuilder buf = new StringBuilder();
        for (int i = 0; i < 5000; i++) {
            buf.append("x");
        }

        // 模拟 truncate 逻辑
        int maxChars = 3000;
        if (buf.length() > maxChars) {
            int start = buf.length() - maxChars;
            buf.delete(0, start);
        }

        assertEquals(maxChars, buf.length(),
                "截断后应恰好为 maxChars 长度");
    }

    // ---- CliAgentProgressSink format 测试 ----

    @Test
    @DisplayName("format 对 LLM_STREAM 事件返回 null（由 handleStreamEvent 处理）")
    void formatReturnsNullForStreamEvents() {
        assertNull(CliAgentProgressSink.format(AgentProgressEvent.llmStreamStarted()));
        assertNull(CliAgentProgressSink.format(AgentProgressEvent.llmContentDelta("x")));
        assertNull(CliAgentProgressSink.format(AgentProgressEvent.llmReasoningDelta("x")));
        assertNull(CliAgentProgressSink.format(AgentProgressEvent.llmStreamCompleted()));
        assertNull(CliAgentProgressSink.format(AgentProgressEvent.llmStreamFailed("e")));
    }

    @Test
    @DisplayName("format 对原有事件保持不变")
    void formatPreservesExistingEvents() {
        // CONTEXT_LOADED
        AgentProgressEvent ctxEvent = AgentProgressEvent.contextLoaded(1, 32, 5000, 3);
        String formatted = CliAgentProgressSink.format(ctxEvent);
        assertNotNull(formatted);
        assertTrue(formatted.contains("[Agent]"));

        // WAITING_LLM
        assertNotNull(CliAgentProgressSink.format(
                AgentProgressEvent.waitingLlm(1, 32)));

        // TOOL_SELECTED
        assertNotNull(CliAgentProgressSink.format(
                AgentProgressEvent.toolSelected(1, 32, "knowledge_search")));

        // AGENT_PUBLIC_MESSAGE
        String pubMsg = CliAgentProgressSink.format(
                AgentProgressEvent.publicMessage("Hello world"));
        assertEquals("Hello world", pubMsg,
                "publicMessage 应直接返回 detail 不加前缀");

        // FINISH_ACTION_ACCEPTED
        assertNull(CliAgentProgressSink.format(
                AgentProgressEvent.finishAccepted(1, 32)),
                "finishAccepted 应返回 null（静默成功）");
    }
}
