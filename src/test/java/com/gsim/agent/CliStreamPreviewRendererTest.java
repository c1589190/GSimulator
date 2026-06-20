package com.gsim.agent;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

/**
 * CliStreamPreviewRenderer 单元测试（重构后 — 基于 LlmStreamSnapshot 渲染）。
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
        // enabled=true, maxChars=3000, showReasoning=true
        renderer = new CliStreamPreviewRenderer(ps, true, 3000, true);
    }

    // ---- snapshot rendering ----

    @Test
    @DisplayName("空 snapshot → 输出包含等待提示")
    void emptySnapshotShowsWaiting() {
        LlmStreamSnapshot snap = new LlmStreamSnapshot(
                "s1", "", "", 0, 0, 0, true, false, null);
        renderer.render(snap);
        String output = baos.toString(StandardCharsets.UTF_8);
        assertTrue(output.contains("等待输出"), "空快照应显示等待，实际: " + output);
    }

    @Test
    @DisplayName("只有 content → 输出区显示内容")
    void contentOnlySnapshotShowsContent() {
        LlmStreamSnapshot snap = new LlmStreamSnapshot(
                "s1", "", "阿道芙进入学校", 0, 2, 0, true, false, null);
        renderer.render(snap);
        String output = baos.toString(StandardCharsets.UTF_8);
        assertTrue(output.contains("阿道芙进入学校"), "应显示 content，实际: " + output);
    }

    @Test
    @DisplayName("只有 reasoning → 思考区显示内容")
    void reasoningOnlySnapshotShowsReasoning() {
        LlmStreamSnapshot snap = new LlmStreamSnapshot(
                "s1", "正在分析玩家行动...", "", 2, 0, 0, true, false, null);
        renderer.render(snap);
        String output = baos.toString(StandardCharsets.UTF_8);
        assertTrue(output.contains("正在分析玩家行动"), "应显示 reasoning，实际: " + output);
    }

    @Test
    @DisplayName("只有 toolCallDelta > 0 → 显示正在选择工具")
    void toolCallOnlySnapshotShowsChoosingTool() {
        LlmStreamSnapshot snap = new LlmStreamSnapshot(
                "s1", "", "", 0, 0, 3, true, false, null);
        renderer.render(snap);
        String output = baos.toString(StandardCharsets.UTF_8);
        assertTrue(output.contains("正在选择工具"), "应有工具选择提示，实际: " + output);
    }

    @Test
    @DisplayName("completed snapshot → 不渲染（clear 应在事件处理中调用）")
    void completedSnapshotDoesNotRender() {
        // completed 且无内容 → 不渲染
        LlmStreamSnapshot snap = new LlmStreamSnapshot(
                "s1", "", "", 0, 0, 0, false, true, null);
        renderer.render(snap);
        String output = baos.toString(StandardCharsets.UTF_8);
        // inactive 且无内容的快照不应渲染
        assertTrue(output.isEmpty(), "inactive 无内容快照不应渲染，实际: " + output);
    }

    @Test
    @DisplayName("disabled 模式下不输出任何内容")
    void disabledRendererDoesNothing() {
        CliStreamPreviewRenderer disabled = new CliStreamPreviewRenderer(
                ps, false, 3000, true);
        LlmStreamSnapshot snap = new LlmStreamSnapshot(
                "s1", "r", "c", 1, 1, 0, true, false, null);
        disabled.render(snap);
        String output = baos.toString(StandardCharsets.UTF_8);
        assertTrue(output.isEmpty(), "disabled 模式不应有输出");
    }

    // ---- clear ----

    @Test
    @DisplayName("clear 不抛异常（幂等）")
    void clearIsIdempotent() {
        assertDoesNotThrow(() -> {
            renderer.clear("s1");
            renderer.clear("s2");
            renderer.clear(null);
        });
    }

    // ---- maxChars truncation ----

    @Test
    @DisplayName("长 content 截断只保留末尾 maxChars")
    void longContentIsTruncated() {
        StringBuilder longText = new StringBuilder();
        for (int i = 0; i < 5000; i++) longText.append("x");
        longText.append("END");

        LlmStreamSnapshot snap = new LlmStreamSnapshot(
                "s1", "", longText.toString(), 0, 1, 0, true, false, null);
        renderer.render(snap);
        String output = baos.toString(StandardCharsets.UTF_8);
        // 应只包含末尾 3000 字符（含 "END"）
        assertTrue(output.contains("END"), "截断后应保留末尾，实际: " + output.substring(Math.max(0, output.length() - 100)));
    }

    // ---- CliAgentProgressSink + registry 集成 ----

    @Test
    @DisplayName("CliAgentProgressSink 流式事件 → 从 registry 取 snapshot 渲染")
    void sinkRoutesStreamEventsToRenderer() {
        LlmStreamStateRegistry registry = new LlmStreamStateRegistry();
        ByteArrayOutputStream renderOut = new ByteArrayOutputStream();
        PrintStream renderPs = new PrintStream(renderOut, true, StandardCharsets.UTF_8);
        CliStreamPreviewRenderer r = new CliStreamPreviewRenderer(renderPs, true, 3000, true);

        CliAgentProgressSink sink = new CliAgentProgressSink(
                ps, true, r, registry);

        String sid = "test-stream-1";
        registry.start(sid);

        // content delta → registry + render
        registry.appendContent(sid, "Hello");
        sink.onProgress(AgentProgressEvent.llmContentDelta(sid, "Hello"));
        String output = renderOut.toString(StandardCharsets.UTF_8);
        assertTrue(output.contains("Hello"), "renderer 应显示 content: " + output);

        registry.complete(sid);
        sink.onProgress(AgentProgressEvent.llmStreamCompleted(sid));
    }

    @Test
    @DisplayName("CliAgentProgressSink 流式失败 → clear + 打印错误")
    void sinkClearsAndPrintsErrorOnStreamFailed() {
        ByteArrayOutputStream errBaos = new ByteArrayOutputStream();
        PrintStream errPs = new PrintStream(errBaos, true, StandardCharsets.UTF_8);

        LlmStreamStateRegistry registry = new LlmStreamStateRegistry();
        String sid = "test-fail-1";
        registry.start(sid);

        CliStreamPreviewRenderer r = new CliStreamPreviewRenderer(ps, true, 3000, true);
        CliAgentProgressSink sink = new CliAgentProgressSink(
                errPs, true, r, registry);

        sink.onProgress(AgentProgressEvent.llmStreamFailed(sid, "连接超时"));

        String errOutput = errBaos.toString(StandardCharsets.UTF_8);
        assertTrue(errOutput.contains("LLM 流式输出失败"),
                "应打印失败信息，实际: " + errOutput);
        assertTrue(errOutput.contains("连接超时"),
                "应包含错误详情，实际: " + errOutput);
    }

    @Test
    @DisplayName("CliAgentProgressSink 无 renderer 时流式事件不崩溃")
    void sinkWithoutRendererDoesNotCrashOnStreamEvents() {
        CliAgentProgressSink sink = new CliAgentProgressSink(ps, true, null, null);

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
                AgentProgressEvent.toolSelected(1, 32, "knowledge_search")));

        String pubMsg = CliAgentProgressSink.format(
                AgentProgressEvent.publicMessage("Hello world"));
        assertEquals("Hello world", pubMsg);

        assertNull(CliAgentProgressSink.format(
                AgentProgressEvent.finishAccepted(1, 32)));
    }

    // ===== Test 2: CliStreamStartedShowsWaitingBoxTest =====

    @Test
    @DisplayName("LLM_STREAM_STARTED 事件 → renderWaiting 输出灰框「等待输出……」")
    void streamStartedRendersWaitingBox() {
        CliStreamPreviewRenderer r = new CliStreamPreviewRenderer(ps, true, 3000, true);

        // 模拟 handleStreamEvent 中的 STARTED 处理
        r.renderWaiting("test-stream");

        String output = baos.toString(StandardCharsets.UTF_8);
        assertTrue(output.contains("等待输出"),
                "STARTED 应渲染等待框，实际: " + output);
    }

    @Test
    @DisplayName("LLM_STREAM_STARTED 通过 sink → handleStreamEvent → renderWaiting")
    void sinkRoutesStreamStartedToWaitingBox() {
        LlmStreamStateRegistry registry = new LlmStreamStateRegistry();
        CliStreamPreviewRenderer r = new CliStreamPreviewRenderer(ps, true, 3000, true);
        CliAgentProgressSink sink = new CliAgentProgressSink(ps, true, r, registry);

        sink.onProgress(AgentProgressEvent.llmStreamStarted("sid-wait"));

        String output = baos.toString(StandardCharsets.UTF_8);
        assertTrue(output.contains("等待输出"),
                "sink 应将 STARTED 转为 waiting 框，实际: " + output);
    }

    // ===== Test 3: CliStreamDeltaRegistryMissFallbackTest =====

    @Test
    @DisplayName("registry miss → renderContentFallback 用 event.detail 直接渲染")
    void registryMissFallbackToEventDetailForContent() {
        // 不预先调用 registry.start() — 模拟 registry miss
        LlmStreamStateRegistry registry = new LlmStreamStateRegistry();
        CliStreamPreviewRenderer r = new CliStreamPreviewRenderer(ps, true, 3000, true);
        CliAgentProgressSink sink = new CliAgentProgressSink(ps, true, r, registry);

        // CONTENT_DELTA without prior start → registry miss → fallback
        sink.onProgress(AgentProgressEvent.llmContentDelta("unknown-stream", "fallback内容"));

        String output = baos.toString(StandardCharsets.UTF_8);
        assertTrue(output.contains("fallback内容"),
                "registry miss 时应 fallback 到 event.detail 渲染，实际: " + output);
        assertTrue(output.contains("[STREAM_TRACE]"),
                "registry miss 时应输出 STREAM_TRACE 日志，实际: " + output);
    }

    @Test
    @DisplayName("registry miss → renderReasoningFallback 用 event.detail 直接渲染")
    void registryMissFallbackToEventDetailForReasoning() {
        LlmStreamStateRegistry registry = new LlmStreamStateRegistry();
        CliStreamPreviewRenderer r = new CliStreamPreviewRenderer(ps, true, 3000, true);
        CliAgentProgressSink sink = new CliAgentProgressSink(ps, true, r, registry);

        sink.onProgress(AgentProgressEvent.llmReasoningDelta("unknown-stream", "思考中……"));

        String output = baos.toString(StandardCharsets.UTF_8);
        assertTrue(output.contains("思考中……"),
                "reasoning fallback 应显示内容，实际: " + output);
    }

    @Test
    @DisplayName("registry miss → renderToolChoosing 显示「正在选择工具……」")
    void registryMissFallbackToToolChoosingForToolCall() {
        LlmStreamStateRegistry registry = new LlmStreamStateRegistry();
        CliStreamPreviewRenderer r = new CliStreamPreviewRenderer(ps, true, 3000, true);
        CliAgentProgressSink sink = new CliAgentProgressSink(ps, true, r, registry);

        sink.onProgress(AgentProgressEvent.llmToolCallDelta("unknown-stream"));

        String output = baos.toString(StandardCharsets.UTF_8);
        assertTrue(output.contains("正在选择工具"),
                "tool_call fallback 应显示选择工具提示，实际: " + output);
    }

    @Test
    @DisplayName("registry miss 时不抛异常（防御性）")
    void registryMissDoesNotThrow() {
        CliStreamPreviewRenderer r = new CliStreamPreviewRenderer(ps, true, 3000, true);
        CliAgentProgressSink sink = new CliAgentProgressSink(ps, true, r, null);

        assertDoesNotThrow(() -> {
            sink.onProgress(AgentProgressEvent.llmStreamStarted("x"));
            sink.onProgress(AgentProgressEvent.llmContentDelta("x", "test"));
            sink.onProgress(AgentProgressEvent.llmReasoningDelta("x", "think"));
            sink.onProgress(AgentProgressEvent.llmToolCallDelta("x"));
            sink.onProgress(AgentProgressEvent.llmStreamCompleted("x"));
        });
    }

    // ===== Test 8: StartedDeltaCompletedRenderSequenceTest =====

    @Test
    @DisplayName("完整渲染序列：STARTED → CONTENT_DELTA → COMPLETED → clear")
    void fullRenderSequenceFromStartToComplete() {
        LlmStreamStateRegistry registry = new LlmStreamStateRegistry();
        CliStreamPreviewRenderer r = new CliStreamPreviewRenderer(ps, true, 3000, true);
        CliAgentProgressSink sink = new CliAgentProgressSink(ps, true, r, registry);

        String sid = "full-seq";

        // 1. STARTED — 模拟 Orchestrator 已调用 registry.start()
        registry.start(sid);
        sink.onProgress(AgentProgressEvent.llmStreamStarted(sid));
        String afterStarted = baos.toString(StandardCharsets.UTF_8);
        assertTrue(afterStarted.contains("等待输出"),
                "STARTED 后应有等待框: " + afterStarted);
        baos.reset();

        // 2. CONTENT_DELTA x3
        registry.appendContent(sid, "第一段，");
        sink.onProgress(AgentProgressEvent.llmContentDelta(sid, "第一段，"));
        registry.appendContent(sid, "第二段，");
        sink.onProgress(AgentProgressEvent.llmContentDelta(sid, "第二段，"));
        registry.appendContent(sid, "第三段。");
        sink.onProgress(AgentProgressEvent.llmContentDelta(sid, "第三段。"));

        String afterDeltas = baos.toString(StandardCharsets.UTF_8);
        assertTrue(afterDeltas.contains("第一段") || afterDeltas.contains("第二段") || afterDeltas.contains("第三段"),
                "delta 后应显示内容: " + afterDeltas);
        baos.reset();

        // 3. COMPLETED — clear 灰框
        registry.complete(sid);
        sink.onProgress(AgentProgressEvent.llmStreamCompleted(sid));

        // verify registry state
        LlmStreamSnapshot snap = registry.snapshot(sid);
        assertFalse(snap.active(), "完成后 stream 不应活跃");
        assertTrue(snap.completed(), "完成后 stream 应标记 completed");
        assertEquals("第一段，第二段，第三段。", snap.content(),
                "registry snapshot 应包含完整累积内容");
        assertEquals(3, snap.contentDeltaCount(), "应有 3 次 content delta");
    }

    @Test
    @DisplayName("渲染序列含 reasoning：STARTED → REASONING_DELTA → CONTENT_DELTA → COMPLETED")
    void renderSequenceWithReasoningBeforeContent() {
        LlmStreamStateRegistry registry = new LlmStreamStateRegistry();
        CliStreamPreviewRenderer r = new CliStreamPreviewRenderer(ps, true, 3000, true);
        CliAgentProgressSink sink = new CliAgentProgressSink(ps, true, r, registry);

        String sid = "reasoning-seq";

        registry.start(sid);
        sink.onProgress(AgentProgressEvent.llmStreamStarted(sid));

        // Reasoning first
        registry.appendReasoning(sid, "分析玩家意图……");
        sink.onProgress(AgentProgressEvent.llmReasoningDelta(sid, "分析玩家意图……"));

        String afterReasoning = baos.toString(StandardCharsets.UTF_8);
        assertTrue(afterReasoning.contains("分析玩家意图"),
                "reasoning 应显示，实际: " + afterReasoning);
        baos.reset();

        // Then content
        registry.appendContent(sid, "最终答复");
        sink.onProgress(AgentProgressEvent.llmContentDelta(sid, "最终答复"));

        String afterContent = baos.toString(StandardCharsets.UTF_8);
        assertTrue(afterContent.contains("最终答复"),
                "content 应显示，实际: " + afterContent);
    }

    @Test
    @DisplayName("COMPLETED 后 clear 不清空 registry（registry 由 Orchestrator 管理生命周期）")
    void clearDoesNotRemoveFromRegistry() {
        LlmStreamStateRegistry registry = new LlmStreamStateRegistry();
        CliStreamPreviewRenderer r = new CliStreamPreviewRenderer(ps, true, 3000, true);
        CliAgentProgressSink sink = new CliAgentProgressSink(ps, true, r, registry);

        String sid = "clear-test";
        registry.start(sid);
        registry.appendContent(sid, "data");

        // 真实流程：Orchestrator 先 complete registry，再发 COMPLETED 事件给 sink
        registry.complete(sid);
        sink.onProgress(AgentProgressEvent.llmStreamCompleted(sid));

        LlmStreamSnapshot snap = registry.snapshot(sid);
        assertFalse(snap.active(), "completed 后 registry 中 stream 不应活跃");
        assertTrue(snap.completed(), "completed 后 stream 应标记 completed");
        // content 仍在（completed 不删除数据）
        assertFalse(snap.content().isEmpty(), "content 应保留在 registry");
    }
}
