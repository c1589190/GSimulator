package com.gsim.agent;

import com.gsim.event.EventBus;
import com.gsim.event.GSimEvent;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 将 AgentProgressEvent 桥接到 EventBus，供 SSE 流式事件使用。
 *
 * <p>使用 ThreadLocal 传递当前 taskId（由 TaskManager 在执行前设置）。
 */
public class EventBusAgentProgressSink implements AgentProgressSink {

    private static final ThreadLocal<String> currentTaskId = new ThreadLocal<>();
    private static final ThreadLocal<String> currentSessionId = new ThreadLocal<>();

    private final EventBus eventBus;

    public EventBusAgentProgressSink(EventBus eventBus) {
        this.eventBus = eventBus;
    }

    /** TaskManager 在执行任务前调用，绑定当前线程的 taskId/sessionId。 */
    public static void bindTask(String sessionId, String taskId) {
        currentSessionId.set(sessionId);
        currentTaskId.set(taskId);
    }

    /** TaskManager 在任务完成后调用，清理绑定。 */
    public static void unbindTask() {
        currentSessionId.remove();
        currentTaskId.remove();
    }

    /** 获取当前线程绑定的 taskId（供 DispatchSubAgentTool 等捕获用）。 */
    public static String getCurrentTaskId() {
        return currentTaskId.get();
    }

    /** 获取当前线程绑定的 sessionId（供 DispatchSubAgentTool 等捕获用）。 */
    public static String getCurrentSessionId() {
        return currentSessionId.get();
    }

    @Override
    public void onProgress(AgentProgressEvent event) {
        String taskId = currentTaskId.get();
        String sessionId = currentSessionId.get();
        Map<String, String> meta = event.meta();
        String agentId = meta.get("agentId");

        // SubAgent 事件：ThreadLocal 为空时从 meta 中获取（由 TaggedAgentProgressSink 注入）
        if (taskId == null) taskId = meta.get("taskId");
        if (sessionId == null) sessionId = meta.get("sessionId");

        if (taskId == null || sessionId == null) return;

        String phase = event.phase();
        String gsimType = mapPhaseToType(phase);
        if (gsimType == null) return;

        Map<String, Object> data = new LinkedHashMap<>();
        switch (gsimType) {
            case "llm_delta" -> {
                String content = event.detail();
                if (content != null && !content.isEmpty()) {
                    data.put("content", content);
                }
            }
            case "llm_reasoning_delta" -> {
                String content = event.detail();
                if (content != null && !content.isEmpty()) {
                    data.put("content", content);
                }
            }
            case "llm_started" -> data.put("streamId", meta.getOrDefault("streamId", ""));
            case "llm_done" -> {}
            case "llm_tool_delta" -> {
                data.put("tool", meta.getOrDefault("tool", ""));
                data.put("index", meta.getOrDefault("index", ""));
            }
            case "tool_started", "tool_done", "tool_error" -> {
                data.put("tool", meta.getOrDefault("tool", ""));
                if (gsimType.equals("tool_error")) {
                    data.put("error", meta.getOrDefault("error", ""));
                }
            }
            case "log" -> {
                data.put("message", event.detail());
                // 透传推文卡片字段
                String subType = meta.get("subType");
                if (subType != null) {
                    data.put("subType", subType);
                    data.put("title", meta.getOrDefault("title", ""));
                    data.put("body", meta.getOrDefault("body", event.detail()));
                }
            }
            default -> {}
        }

        // 透传 agentId（SubAgent 事件路由用）
        if (agentId != null) {
            data.put("agentId", agentId);
        }

        eventBus.publish(GSimEvent.of(sessionId, taskId, gsimType, data));
    }

    private static String mapPhaseToType(String phase) {
        return switch (phase) {
            case AgentProgressEvent.LLM_STREAM_STARTED -> "llm_started";
            case AgentProgressEvent.LLM_CONTENT_DELTA -> "llm_delta";
            case AgentProgressEvent.LLM_REASONING_DELTA -> "llm_reasoning_delta";
            case AgentProgressEvent.LLM_STREAM_COMPLETED -> "llm_done";
            case AgentProgressEvent.LLM_STREAM_FAILED -> "llm_error";
            case AgentProgressEvent.LLM_TOOL_CALL_DELTA -> "llm_tool_delta";
            case AgentProgressEvent.TOOL_SELECTED, AgentProgressEvent.TOOL_EXECUTING -> "tool_started";
            case AgentProgressEvent.TOOL_SUCCESS -> "tool_done";
            case AgentProgressEvent.TOOL_FAILED -> "tool_error";
            case AgentProgressEvent.AGENT_PUBLIC_MESSAGE -> "log";
            default -> null;
        };
    }
}
