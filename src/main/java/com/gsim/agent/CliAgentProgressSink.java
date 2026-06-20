package com.gsim.agent;

import java.io.PrintStream;

/**
 * CLI 模式 AgentProgressSink 实现。
 * 格式化 AgentProgressEvent 为简短状态行打印到 System.out。
 * 支持 LLM 流式预览灰框 — 通过 LlmStreamStateRegistry 获取快照渲染。
 */
public class CliAgentProgressSink implements AgentProgressSink {

    private final PrintStream out;
    private final boolean enabled;
    private final CliStreamPreviewRenderer streamRenderer;
    private final LlmStreamStateRegistry streamRegistry;

    public CliAgentProgressSink(PrintStream out) {
        this(out, true, null, null);
    }

    public CliAgentProgressSink(PrintStream out, boolean enabled) {
        this(out, enabled, null, null);
    }

    public CliAgentProgressSink(PrintStream out, boolean enabled,
                                CliStreamPreviewRenderer streamRenderer) {
        this(out, enabled, streamRenderer, null);
    }

    /**
     * @param out            输出流
     * @param enabled        是否启用常规进度输出
     * @param streamRenderer LLM 流式预览渲染器（可为 null，则禁用流式预览）
     * @param streamRegistry LLM 流式状态注册表（可为 null，从 event meta 直接读）
     */
    public CliAgentProgressSink(PrintStream out, boolean enabled,
                                CliStreamPreviewRenderer streamRenderer,
                                LlmStreamStateRegistry streamRegistry) {
        this.out = out;
        this.enabled = enabled;
        this.streamRenderer = streamRenderer;
        this.streamRegistry = streamRegistry;
    }

    @Override
    public void onProgress(AgentProgressEvent event) {
        if (event == null) return;

        // LLM 流式事件由 streamRenderer 处理（独立于 enabled 开关）
        if (handleStreamEvent(event)) return;

        if (!enabled) return;
        String line = format(event);
        if (line != null && !line.isBlank()) {
            out.println(line);
        }
    }

    /**
     * 处理 LLM 流式事件。返回 true 表示已处理（不需要再走 format）。
     * 优先从 registry 获取 snapshot 渲染；registry miss 时 fallback 到 event.detail 直接渲染。
     */
    private boolean handleStreamEvent(AgentProgressEvent event) {
        if (streamRenderer == null) return false;

        String streamId = event.meta() != null ? event.meta().get("streamId") : null;

        return switch (event.phase()) {
            case AgentProgressEvent.LLM_STREAM_STARTED -> {
                // STARTED 必须渲染等待框（不依赖 registry）
                streamRenderer.renderWaiting(streamId);
                yield true;
            }
            case AgentProgressEvent.LLM_CONTENT_DELTA -> {
                LlmStreamSnapshot snapshot = getSnapshot(streamId);
                if (snapshot != null && snapshot.hasAnyContent()) {
                    streamRenderer.render(snapshot);
                } else {
                    // registry miss fallback：直接用 event.detail 渲染
                    logRegistryMiss(streamId, "LLM_CONTENT_DELTA");
                    String delta = event.detail();
                    if (delta != null && !delta.isEmpty()) {
                        streamRenderer.renderContentFallback(streamId, delta);
                    }
                }
                yield true;
            }
            case AgentProgressEvent.LLM_REASONING_DELTA -> {
                LlmStreamSnapshot snapshot = getSnapshot(streamId);
                if (snapshot != null && snapshot.hasAnyContent()) {
                    streamRenderer.render(snapshot);
                } else {
                    logRegistryMiss(streamId, "LLM_REASONING_DELTA");
                    String delta = event.detail();
                    if (delta != null && !delta.isEmpty()) {
                        streamRenderer.renderReasoningFallback(streamId, delta);
                    }
                }
                yield true;
            }
            case AgentProgressEvent.LLM_TOOL_CALL_DELTA -> {
                LlmStreamSnapshot snapshot = getSnapshot(streamId);
                if (snapshot != null && snapshot.toolCallDeltaCount() > 0) {
                    streamRenderer.render(snapshot);
                } else {
                    logRegistryMiss(streamId, "LLM_TOOL_CALL_DELTA");
                    streamRenderer.renderToolChoosing(streamId);
                }
                yield true;
            }
            case AgentProgressEvent.LLM_STREAM_COMPLETED -> {
                streamRenderer.clear(streamId);
                yield true;
            }
            case AgentProgressEvent.LLM_STREAM_FAILED -> {
                streamRenderer.clear(streamId);
                String error = event.meta().getOrDefault("error", "未知错误");
                out.println("[Agent] LLM 流式输出失败: " + error);
                yield true;
            }
            default -> false;
        };
    }

    private void logRegistryMiss(String streamId, String eventType) {
        out.println("[STREAM_TRACE] registry miss streamId=" + streamId
                + " event=" + eventType);
    }

    /** 从 registry 获取 snapshot。若无 registry 则返回 null（触发 fallback）。 */
    private LlmStreamSnapshot getSnapshot(String streamId) {
        if (streamRegistry != null && streamId != null) {
            return streamRegistry.snapshot(streamId);
        }
        return null;
    }

    /** 格式化非流式事件为简短状态行。保证长度 ≤ 120 chars。 */
    static String format(AgentProgressEvent event) {
        if (event == null) return null;
        return switch (event.phase()) {
            case AgentProgressEvent.CONTEXT_LOADED -> {
                int chars = parseIntMeta(event.meta(), "requestChars");
                int tools = parseIntMeta(event.meta(), "toolCount");
                String activeBranch = event.meta().getOrDefault("activeBranch", "");
                String contextMode = event.meta().getOrDefault("contextMode", "");
                yield "[Agent] 上下文：activeBranch=" + activeBranch
                        + "，mode=" + contextMode
                        + "，requestChars=" + chars
                        + "，tools=" + tools + " 个";
            }
            case AgentProgressEvent.WAITING_LLM ->
                    "[Agent] 正在等待 LLM 选择工具……";
            case AgentProgressEvent.TOOL_SELECTED -> {
                String tool = event.meta().getOrDefault("tool", "");
                yield "[Agent] LLM 选择工具：" + tool;
            }
            case AgentProgressEvent.TOOL_EXECUTING -> {
                String tool = event.meta().getOrDefault("tool", "");
                yield "[Agent] 正在执行工具：" + tool;
            }
            case AgentProgressEvent.TOOL_SUCCESS -> {
                String tool = event.meta().getOrDefault("tool", "");
                yield "[Agent] 工具成功：" + tool;
            }
            case AgentProgressEvent.TOOL_FAILED -> {
                String tool = event.meta().getOrDefault("tool", "");
                String error = event.meta().getOrDefault("error", "");
                yield "[Agent] 工具失败：" + tool + (error.isBlank() ? "" : "，原因：" + error);
            }
            case AgentProgressEvent.AWAITING_FINISH_ACTION ->
                    "[Agent] 正在让 LLM 根据工具结果生成 finish_action……";
            case AgentProgressEvent.PLAIN_ANSWER_WITHOUT_FINISH ->
                    "[Agent] " + event.detail();
            case AgentProgressEvent.INVALID_BRACKET_INTENT ->
                    "[Agent] " + event.detail();
            case AgentProgressEvent.FINISH_ACTION_REJECTED ->
                    "[Agent] finish_action 被拒绝：" + reasonText(
                            event.meta().getOrDefault("rejectReason", ""));
            case AgentProgressEvent.FINISH_ACTION_ACCEPTED ->
                    null;
            case AgentProgressEvent.AGENT_PUBLIC_MESSAGE ->
                    event.detail();
            case AgentProgressEvent.ABORTED ->
                    "[Agent] " + event.detail();
            // LLM 流式事件不应走到这里（已在 handleStreamEvent 处理）
            case AgentProgressEvent.LLM_STREAM_STARTED,
                 AgentProgressEvent.LLM_CONTENT_DELTA,
                 AgentProgressEvent.LLM_REASONING_DELTA,
                 AgentProgressEvent.LLM_TOOL_CALL_DELTA,
                 AgentProgressEvent.LLM_STREAM_COMPLETED,
                 AgentProgressEvent.LLM_STREAM_FAILED -> null;
            default -> null;
        };
    }

    static String reasonText(String reasonCode) {
        if (reasonCode == null) return "未知原因";
        return switch (reasonCode) {
            case "FINISH_ACTION_WITH_OTHER_TOOLS" ->
                    "与其他工具同轮混用，要求模型先单独执行工具。";
            case "CLAIM_WITHOUT_SUPPORTING_TOOL_RESULT" ->
                    "声称了未经真实工具执行支持的结果。";
            default -> reasonCode;
        };
    }

    private static int parseIntMeta(java.util.Map<String, String> meta, String key) {
        if (meta == null) return 0;
        try {
            return Integer.parseInt(meta.getOrDefault(key, "0"));
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}
