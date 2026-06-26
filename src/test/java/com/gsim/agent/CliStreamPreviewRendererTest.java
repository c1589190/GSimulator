package com.gsim.agent;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

/**
 * CliStreamPreviewRenderer 单元测试（自管理缓冲区，无外部 registry）。
 * 重构后 sink 不再持有 renderer — renderer 纯独立使用。
 * sink 集成测试改为验证直接 inline 打印行为。
 */
@DisplayName("CLI 流式预览渲染器 + sink inline 打印测试")
class CliStreamPreviewRendererTest {

    private ByteArrayOutputStream baos;
    private PrintStream ps;
    private CliStreamPreviewRenderer renderer;

    @BeforeEach
    void setUp() {
        baos = new ByteArrayOutputStream();
        ps = new PrintStream(baos, true, StandardCharsets.UTF_8);
        // enabled=true, maxChars=3000, showReasoning=true
        renderer = new CliStreamPreviewRenderer(ps, true, 3000, true);
    }

    // ---- renderWaiting ----

    @Test
    @DisplayName("renderWaiting 输出灰框「等待输出……」")
    void renderWaitingShowsWaitingBox() {
        renderer.renderWaiting("s1");
        String output = baos.toString(StandardCharsets.UTF_8);
        assertTrue(output.contains("等待输出"), "renderWaiting 应显示等待，实际: " + output);
    }

    // ---- appendContent ----

    @Test
    @DisplayName("appendContent → 输出区显示内容")
    void appendContentShowsContent() {
        renderer.appendContent("s1", "阿道芙进入学校");
        String output = baos.toString(StandardCharsets.UTF_8);
        assertTrue(output.contains("阿道芙进入学校"), "应显示 content，实际: " + output);
    }

    // ---- appendReasoning ----

    @Test
    @DisplayName("appendReasoning → 思考区显示内容")
    void appendReasoningShowsReasoning() {
        renderer.appendReasoning("s1", "正在分析玩家行动...");
        String output = baos.toString(StandardCharsets.UTF_8);
        assertTrue(output.contains("正在分析玩家行动"), "应显示 reasoning，实际: " + output);
    }

    // ---- markToolCallDelta ----

    @Test
    @DisplayName("markToolCallDelta → 显示正在选择工具")
    void toolCallDeltaShowsChoosingTool() {
        renderer.markToolCallDelta("s1");
        String output = baos.toString(StandardCharsets.UTF_8);
        assertTrue(output.contains("正在选择工具"), "应有工具选择提示，实际: " + output);
    }

    // ---- clear ----

    @Test
    @DisplayName("clear 不抛异常（幂等）")
    void clearIsIdempotent() {
        assertDoesNotThrow(() -> {
            renderer.clear("s1");
            renderer.clear("s2");
            renderer.clearIfActive();
        });
    }

    // ---- clearIfActive ----

    @Test
    @DisplayName("无活跃框时 clearIfActive 不抛异常")
    void clearIfActiveWhenNothingActive() {
        assertDoesNotThrow(() -> renderer.clearIfActive());
    }

    @Test
    @DisplayName("有活跃内容后 clearIfActive 清除灰框")
    void clearIfActiveClearsActiveBox() {
        renderer.appendContent("s1", "hello");
        renderer.clearIfActive();
    }

    // ---- maxChars truncation ----

    @Test
    @DisplayName("长 content 截断只保留末尾 maxChars")
    void longContentIsTruncated() {
        StringBuilder longText = new StringBuilder();
        for (int i = 0; i < 5000; i++) longText.append("x");
        longText.append("END");

        renderer.appendContent("s1", longText.toString());
        String output = baos.toString(StandardCharsets.UTF_8);
        assertTrue(output.contains("END"), "截断后应保留末尾，实际: " + output.substring(Math.max(0, output.length() - 100)));
    }

    // ---- disabled mode ----

    @Test
    @DisplayName("disabled 模式下不输出任何内容")
    void disabledRendererDoesNothing() {
        ByteArrayOutputStream disabledBaos = new ByteArrayOutputStream();
        PrintStream disabledPs = new PrintStream(disabledBaos, true, StandardCharsets.UTF_8);
        CliStreamPreviewRenderer disabled = new CliStreamPreviewRenderer(disabledPs, false, 3000, true);

        disabled.renderWaiting("s1");
        disabled.appendContent("s1", "content");
        disabled.appendReasoning("s1", "reasoning");
        disabled.markToolCallDelta("s1");

        String output = disabledBaos.toString(StandardCharsets.UTF_8);
        assertTrue(output.isEmpty(), "disabled 模式不应有输出，实际: " + output);
    }

    // ---- CliAgentProgressSink inline 打印集成测试 ----

    @Test
    @DisplayName("LLM_STREAM_STARTED 通过 sink → 直接输出 [...] 前缀")
    void sinkPrintsStartMarkerOnStreamStarted() {
        CliAgentProgressSink sink = new CliAgentProgressSink(ps, true);
        sink.onProgress(AgentProgressEvent.llmStreamStarted("sid-wait"));

        String output = baos.toString(StandardCharsets.UTF_8);
        assertTrue(output.contains("[...]"), "sink 应将 STARTED 转为 [...] 前缀，实际: " + output);
    }

    @Test
    @DisplayName("LLM_CONTENT_DELTA 通过 sink → 粗体高亮直接打印 delta")
    void sinkPrintsContentDeltaDirectly() {
        CliAgentProgressSink sink = new CliAgentProgressSink(ps, true);

        sink.onProgress(AgentProgressEvent.llmStreamStarted("s1"));
        baos.reset();

        sink.onProgress(AgentProgressEvent.llmContentDelta("s1", "粗体输出内容"));
        String output = baos.toString(StandardCharsets.UTF_8);
        assertTrue(output.contains("粗体输出内容"), "sink 应直接打印 delta: " + output);
        assertTrue(output.contains("\033[1m"), "首个 content 应以粗体 ANSI 开头: " + output);
    }

    @Test
    @DisplayName("COMPLETED 关闭粗体 ANSI，恢复正常文字")
    void completedResetsBoldAnsi() {
        CliAgentProgressSink sink = new CliAgentProgressSink(ps, true);

        sink.onProgress(AgentProgressEvent.llmStreamStarted("s1"));
        sink.onProgress(AgentProgressEvent.llmContentDelta("s1", "content"));
        baos.reset();

        sink.onProgress(AgentProgressEvent.llmStreamCompleted("s1"));
        String output = baos.toString(StandardCharsets.UTF_8);
        assertTrue(output.contains("\033[0m"), "COMPLETED 应关闭粗体 ANSI");
    }

    @Test
    @DisplayName("LLM_STREAM_FAILED 通过 sink → 打印错误（无 renderer 参与）")
    void sinkPrintsErrorOnStreamFailedWithoutRenderer() {
        ByteArrayOutputStream errBaos = new ByteArrayOutputStream();
        PrintStream errPs = new PrintStream(errBaos, true, StandardCharsets.UTF_8);
        CliAgentProgressSink sink = new CliAgentProgressSink(errPs, true);

        sink.onProgress(AgentProgressEvent.llmStreamFailed("test-fail", "连接超时"));

        String errOutput = errBaos.toString(StandardCharsets.UTF_8);
        assertTrue(errOutput.contains("连接超时"),
                "应包含错误详情，实际: " + errOutput);
    }

    @Test
    @DisplayName("流式事件不抛异常（防御性）")
    void sinkHandlesAllStreamEventsWithoutException() {
        CliAgentProgressSink sink = new CliAgentProgressSink(ps, true);

        assertDoesNotThrow(() -> {
            sink.onProgress(AgentProgressEvent.llmStreamStarted("x"));
            sink.onProgress(AgentProgressEvent.llmContentDelta("x", "test"));
            sink.onProgress(AgentProgressEvent.llmReasoningDelta("x", "think"));
            sink.onProgress(AgentProgressEvent.llmToolCallDelta("x"));
            sink.onProgress(AgentProgressEvent.llmStreamCompleted("x"));
            sink.onProgress(AgentProgressEvent.llmStreamFailed("x", "error"));
        });
    }

    // ---- format 测试 ----

    @Test
    @DisplayName("format 对 LLM_STREAM 事件返回 null（由 handleStreamEvent 处理）")
    void formatReturnsNullForStreamEvents() {
        assertNull(CliAgentProgressSink.format(AgentProgressEvent.llmStreamStarted("x")));
        assertNull(CliAgentProgressSink.format(AgentProgressEvent.llmContentDelta("x", "y")));
        assertNull(CliAgentProgressSink.format(AgentProgressEvent.llmReasoningDelta("x", "y")));
        assertNull(CliAgentProgressSink.format(AgentProgressEvent.llmToolCallDelta("x")));
        assertNull(CliAgentProgressSink.format(AgentProgressEvent.llmStreamCompleted("x")));
        assertNull(CliAgentProgressSink.format(AgentProgressEvent.llmStreamFailed("x", "e")));
    }

    @Test
    @DisplayName("format 对原有事件保持不变")
    void formatPreservesExistingEvents() {
        assertNotNull(CliAgentProgressSink.format(
                AgentProgressEvent.contextLoaded(1, 32, 5000, 3)));
        assertNotNull(CliAgentProgressSink.format(
                AgentProgressEvent.waitingLlm(1, 32)));
        assertNotNull(CliAgentProgressSink.format(
                AgentProgressEvent.toolSelected(1, 32, "query_keyword")));

        String pubMsg = CliAgentProgressSink.format(
                AgentProgressEvent.publicMessage("Hello world"));
        assertEquals("Hello world", pubMsg);

        assertNull(CliAgentProgressSink.format(
                AgentProgressEvent.finishAccepted(1, 32)));
    }

    @Test
    @DisplayName("REASONING_DELTA 以灰色 Thinking: 前缀直接打印")
    void reasoningDeltaPrintsWithGreyThinkingPrefix() {
        CliAgentProgressSink sink = new CliAgentProgressSink(ps, true);

        sink.onProgress(AgentProgressEvent.llmStreamStarted("s1"));
        baos.reset();

        // 第一个 reasoning delta 应有 Thinking: 前缀 + 灰色
        sink.onProgress(AgentProgressEvent.llmReasoningDelta("s1", "分析中……"));
        String first = baos.toString(StandardCharsets.UTF_8);
        assertTrue(first.contains("Thinking:"), "首个 reasoning 应有 Thinking: 前缀: " + first);
        assertTrue(first.contains("\033[90m"), "应有灰色 ANSI 开始码: " + first);
        baos.reset();

        // 第二个 reasoning delta 不应重复 Thinking: 前缀
        sink.onProgress(AgentProgressEvent.llmReasoningDelta("s1", "继续分析"));
        String second = baos.toString(StandardCharsets.UTF_8);
        assertFalse(second.contains("Thinking:"), "后续 reasoning 不应重复前缀: " + second);
        assertTrue(second.contains("继续分析"), "应继续打印内容: " + second);
    }

    @Test
    @DisplayName("REASONING_DELTA 结束后 COMPLETED 关闭 ANSI 灰色")
    void reasoningClosedOnStreamCompleted() {
        CliAgentProgressSink sink = new CliAgentProgressSink(ps, true);

        sink.onProgress(AgentProgressEvent.llmStreamStarted("s1"));
        sink.onProgress(AgentProgressEvent.llmReasoningDelta("s1", "思考内容"));
        baos.reset();

        sink.onProgress(AgentProgressEvent.llmStreamCompleted("s1"));
        String output = baos.toString(StandardCharsets.UTF_8);
        assertTrue(output.contains("\033[0m"), "COMPLETED 时应关闭 ANSI 灰色");
    }

    // ---- 完整 inline 打印序列 ----

    @Test
    @DisplayName("完整 inline 序列：STARTED → CONTENT_DELTA → COMPLETED")
    void fullInlineSequenceFromStartToComplete() {
        CliAgentProgressSink sink = new CliAgentProgressSink(ps, true);
        String sid = "full-seq";

        // 1. STARTED
        sink.onProgress(AgentProgressEvent.llmStreamStarted(sid));
        String afterStarted = baos.toString(StandardCharsets.UTF_8);
        assertTrue(afterStarted.contains("[...]"), "STARTED 后应有 [...] 前缀: " + afterStarted);
        baos.reset();

        // 2. CONTENT_DELTA x3
        sink.onProgress(AgentProgressEvent.llmContentDelta(sid, "第一段，"));
        sink.onProgress(AgentProgressEvent.llmContentDelta(sid, "第二段，"));
        sink.onProgress(AgentProgressEvent.llmContentDelta(sid, "第三段。"));

        String afterDeltas = baos.toString(StandardCharsets.UTF_8);
        assertTrue(afterDeltas.contains("第一段") || afterDeltas.contains("第二段") || afterDeltas.contains("第三段"),
                "delta 后应显示内容: " + afterDeltas);
        baos.reset();

        // 3. COMPLETED
        sink.onProgress(AgentProgressEvent.llmStreamCompleted(sid));
        // 换行追加 — 验证不抛异常
        assertDoesNotThrow(() -> {});
    }

    @Test
    @DisplayName("inline 序列含 reasoning：STARTED → REASONING_DELTA → CONTENT_DELTA")
    void inlineSequenceWithReasoningBeforeContent() {
        CliAgentProgressSink sink = new CliAgentProgressSink(ps, true);
        String sid = "reasoning-seq";

        sink.onProgress(AgentProgressEvent.llmStreamStarted(sid));

        // Reasoning first — 应以灰色 Thinking: 前缀打印
        sink.onProgress(AgentProgressEvent.llmReasoningDelta(sid, "分析玩家意图……"));

        String afterReasoning = baos.toString(StandardCharsets.UTF_8);
        assertTrue(afterReasoning.contains("Thinking:"), "reasoning 应以 Thinking: 前缀打印，实际: " + afterReasoning);
        assertTrue(afterReasoning.contains("\033[90m"), "reasoning 应有灰色 ANSI，实际: " + afterReasoning);
        assertTrue(afterReasoning.contains("分析玩家意图"), "reasoning 应直接打印内容，实际: " + afterReasoning);
        baos.reset();

        // Then content — 应先关闭 reasoning 灰色并换行
        sink.onProgress(AgentProgressEvent.llmContentDelta(sid, "最终答复"));

        String afterContent = baos.toString(StandardCharsets.UTF_8);
        assertTrue(afterContent.contains("\033[0m"), "切换到 content 时应重置 ANSI");
        assertTrue(afterContent.contains("最终答复"), "content 应直接打印，实际: " + afterContent);
    }

    @Test
    @DisplayName("COMPLETED 后 renderer 缓冲区被清空（独立于 sink）")
    void clearRemovesBuffersAfterComplete() {
        String sid = "clear-test";
        renderer.appendContent(sid, "hello world");
        renderer.clear(sid);

        // clear 后重新 append 不应包含旧内容
        ByteArrayOutputStream baos2 = new ByteArrayOutputStream();
        PrintStream ps2 = new PrintStream(baos2, true, StandardCharsets.UTF_8);
        CliStreamPreviewRenderer r2 = new CliStreamPreviewRenderer(ps2, true, 3000, true);

        r2.appendContent(sid, "fresh内容");
        String output = baos2.toString(StandardCharsets.UTF_8);
        assertTrue(output.contains("fresh内容"), "clear 后新 append 应正常渲染");
        assertFalse(output.contains("hello world"), "clear 后不应包含旧内容");
    }

    // ---- renderer 独立于 sink 运行 ----

    @Test
    @DisplayName("renderer 灰框输出与 sink 无关（独立渲染）")
    void rendererOperatesIndependentlyOfSink() {
        // renderer 独立渲染灰框
        renderer.renderWaiting("stream-1");
        ps.flush();
        assertTrue(baos.toString().contains("\033[90m"), "渲染后应有 ANSI 灰框输出");

        // sink 处理非流式事件 — 不再清除灰框
        var sink = new CliAgentProgressSink(ps, true);
        var event = AgentProgressEvent.plainAnswerWithoutFinish(2, 10);
        sink.onProgress(event);

        String output = baos.toString();
        assertTrue(output.contains("[Agent]"), "非流式事件后应格式化 [Agent] 行");
    }

    @Test
    @DisplayName("无 renderer 时非流式事件也正常处理")
    void nonStreamEventsNormalWithoutRenderer() {
        CliAgentProgressSink sink = new CliAgentProgressSink(ps, true);
        var event = AgentProgressEvent.plainAnswerWithoutFinish(2, 10);
        assertDoesNotThrow(() -> sink.onProgress(event));
    }
}
