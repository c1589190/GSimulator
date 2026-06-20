package com.gsim.llm;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 流式事件池 — 线程安全的实时流式数据累积器。
 *
 * <p>写入端（package-private，只有 LlmManager/Provider/SseParser 可写）：
 * <ul>
 *   <li>{@link #onContentDelta(String)} — 累积 content</li>
 *   <li>{@link #onReasoningDelta(String)} — 累积 reasoning</li>
 *   <li>{@link #onToolCallDelta(int, String, String)} — 累积 tool_call delta</li>
 *   <li>{@link #onComplete(LlmResult)} — 标记完成</li>
 *   <li>{@link #onError(String)} — 标记失败</li>
 * </ul>
 *
 * <p>读取端（public，Agent 和其他消费者可读）：
 * <ul>
 *   <li>{@link #getContent()} / {@link #getReasoning()} — 当前累积文本</li>
 *   <li>{@link #isComplete()} / {@link #isSuccess()} — 状态</li>
 *   <li>{@link #getToolCalls()} — 已组装的 tool calls</li>
 *   <li>{@link #awaitCompletion(long)} — 阻塞等待完成</li>
 *   <li>{@link #getEvents()} — 事件快照（调试）</li>
 * </ul>
 */
public class StreamPool {

    public enum EventType { CONTENT, REASONING, TOOL_CALL_DELTA, COMPLETE, ERROR }

    public record PoolEvent(EventType type, String data, long timestampMs) {}

    // ---- 累积缓冲 ----

    private final String streamId;
    private final StringBuilder contentBuf = new StringBuilder();
    private final StringBuilder reasoningBuf = new StringBuilder();

    // tool_call 增量合并缓冲区
    private final List<ToolCallMergeBuffer> toolCallBuffers = new ArrayList<>();
    private final List<LlmToolCall> assembledToolCalls = new ArrayList<>();

    // ---- 状态 ----

    private final CountDownLatch completionLatch = new CountDownLatch(1);
    private final AtomicReference<LlmResult> finalResult = new AtomicReference<>();
    private final List<PoolEvent> events = new CopyOnWriteArrayList<>();

    private volatile boolean complete = false;
    private volatile boolean success = false;
    private volatile String errorMessage = null;
    private volatile long startTimeMs;
    private volatile long endTimeMs;

    public StreamPool(String streamId) {
        this.streamId = streamId;
        this.startTimeMs = System.currentTimeMillis();
    }

    // ═══════════════════════════════════════════════
    // 写入接口（package-private）
    // ═══════════════════════════════════════════════

    public void onContentDelta(String text) {
        if (text == null || text.isEmpty()) return;
        synchronized (contentBuf) {
            contentBuf.append(text);
        }
        events.add(new PoolEvent(EventType.CONTENT, text, System.currentTimeMillis()));
    }

    public void onReasoningDelta(String text) {
        if (text == null || text.isEmpty()) return;
        synchronized (reasoningBuf) {
            reasoningBuf.append(text);
        }
        events.add(new PoolEvent(EventType.REASONING, text, System.currentTimeMillis()));
    }

    /**
     * 接收一个 tool_call delta。
     * @param index  tool_call 在数组中的下标（0-based）
     * @param name   function.name（只在首帧出现，后续为 null）
     * @param argsChunk function.arguments 的增量 JSON 片段
     */
    public void onToolCallDelta(int index, String name, String argsChunk) {
        synchronized (toolCallBuffers) {
            while (toolCallBuffers.size() <= index) {
                toolCallBuffers.add(new ToolCallMergeBuffer());
            }
            ToolCallMergeBuffer buf = toolCallBuffers.get(index);
            if (name != null) buf.name = name;
            if (argsChunk != null) buf.argsBuilder.append(argsChunk);
        }
        events.add(new PoolEvent(EventType.TOOL_CALL_DELTA,
                "index=" + index + (name != null ? " name=" + name : ""),
                System.currentTimeMillis()));
    }

    public void onComplete(LlmResult result) {
        endTimeMs = System.currentTimeMillis();
        complete = true;
        success = result.success();
        errorMessage = result.errorMessage();

        // 组装 tool_calls（从缓冲区合并最终参数）
        List<LlmToolCall> merged;
        synchronized (toolCallBuffers) {
            merged = new ArrayList<>();
            // 优先用 result 自带的 toolCalls（非流式路径），否则用 SSE 累积的
            if (result.hasApiToolCalls()) {
                merged.addAll(result.toolCalls());
            } else {
                for (int i = 0; i < toolCallBuffers.size(); i++) {
                    ToolCallMergeBuffer buf = toolCallBuffers.get(i);
                    if (buf.name != null) {
                        String argsJson = buf.argsBuilder.toString();
                        Map<String, String> args = parseArgsJson(argsJson);
                        merged.add(new LlmToolCall("call_" + streamId + "_" + i, buf.name, args));
                    }
                }
            }
            assembledToolCalls.addAll(merged);
        }

        // 构建最终 LlmResult（合并流式累积的 content/reasoning + toolCalls）
        String content = getContent();
        String reasoning = getReasoning();
        LlmResult mergedResult = new LlmResult(
                !content.isEmpty() ? content : result.content(),
                !reasoning.isEmpty() ? reasoning : result.reasoning(),
                result.model(),
                result.tokensUsed(),
                result.success(),
                result.errorMessage(),
                merged,
                result.finishReason()
        );
        finalResult.set(mergedResult);

        events.add(new PoolEvent(EventType.COMPLETE, "", System.currentTimeMillis()));
        completionLatch.countDown();
    }

    public void onError(String message) {
        endTimeMs = System.currentTimeMillis();
        complete = true;
        success = false;
        errorMessage = message;
        finalResult.set(LlmResult.failure(message));
        events.add(new PoolEvent(EventType.ERROR, message, System.currentTimeMillis()));
        completionLatch.countDown();
    }

    // ═══════════════════════════════════════════════
    // 读取接口（public）
    // ═══════════════════════════════════════════════

    public String streamId() { return streamId; }

    /** 当前已累积的完整 content 文本。 */
    public String getContent() {
        synchronized (contentBuf) {
            return contentBuf.toString();
        }
    }

    /** 当前已累积的完整 reasoning 文本。 */
    public String getReasoning() {
        synchronized (reasoningBuf) {
            return reasoningBuf.toString();
        }
    }

    /** 已组装的 tool calls。流式过程中返回部分结果，完成后返回最终列表。 */
    public List<LlmToolCall> getToolCalls() {
        synchronized (toolCallBuffers) {
            return List.copyOf(assembledToolCalls);
        }
    }

    public boolean isComplete() { return complete; }
    public boolean isSuccess() { return success; }
    public String getError() { return errorMessage; }

    /** 获取最终结果（完成前返回 null）。 */
    public LlmResult getFinalResult() { return finalResult.get(); }

    /** 阻塞等待流式完成，超时返回 false。 */
    public boolean awaitCompletion(long timeoutMs) throws InterruptedException {
        return completionLatch.await(timeoutMs, TimeUnit.MILLISECONDS);
    }

    /** 耗时（毫秒）。完成前返回 -1。 */
    public long elapsedMs() {
        if (startTimeMs == 0) return -1;
        if (endTimeMs == 0) return System.currentTimeMillis() - startTimeMs;
        return endTimeMs - startTimeMs;
    }

    /** 事件快照（调试用）。 */
    public List<PoolEvent> getEvents() { return List.copyOf(events); }

    /** 按类型统计事件数。 */
    public long eventCount(EventType type) {
        return events.stream().filter(e -> e.type() == type).count();
    }

    /** 简短报告。 */
    public String report() {
        StringBuilder sb = new StringBuilder();
        sb.append("StreamPool[").append(streamId).append("] ");
        sb.append("complete=").append(complete).append(" success=").append(success);
        sb.append(" elapsed=").append(elapsedMs()).append("ms");
        sb.append(" contentChars=").append(getContent().length());
        sb.append(" reasoningChars=").append(getReasoning().length());
        sb.append(" toolCalls=").append(getToolCalls().size());
        if (errorMessage != null) sb.append(" error=").append(errorMessage);
        sb.append(" events: CONTENT=").append(eventCount(EventType.CONTENT));
        sb.append(" REASONING=").append(eventCount(EventType.REASONING));
        sb.append(" TOOL_CALL_DELTA=").append(eventCount(EventType.TOOL_CALL_DELTA));
        return sb.toString();
    }

    // ═══════════════════════════════════════════════
    // 内部
    // ═══════════════════════════════════════════════

    private static class ToolCallMergeBuffer {
        String name;
        final StringBuilder argsBuilder = new StringBuilder();
    }

    /** 简单解析 JSON args 字符串为 Map<String,String>。 */
    @SuppressWarnings("unchecked")
    private static Map<String, String> parseArgsJson(String json) {
        if (json == null || json.isBlank()) return Map.of();
        try {
            // 使用 Jackson 解析（与项目一致）
            com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
            Map<String, Object> raw = mapper.readValue(json, Map.class);
            Map<String, String> result = new LinkedHashMap<>();
            for (var entry : raw.entrySet()) {
                Object v = entry.getValue();
                result.put(entry.getKey(), v != null ? v.toString() : "");
            }
            return result;
        } catch (Exception e) {
            // JSON 不完整或格式错误 → 返回空
            return Map.of();
        }
    }
}
