package com.gsim.session;

import com.gsim.agent.AgentProgressEvent;
import com.gsim.agent.AgentProgressSink;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 桥接 AgentProgressEvent → SessionNode，替代 EventBusAgentProgressSink。
 *
 * <p>实现了 {@link AgentProgressSink}，在接收 AgentProgressEvent 时转换为
 * SessionPool 节点操作（pushNode / updateNode / transitionStatus）。
 *
 * <p>用法：
 * <pre>{@code
 * SessionPool pool = new SessionPool();
 * SessionPoolBridge bridge = new SessionPoolBridge(pool, "session-1");
 * orchestratorAgent.setProgressSink(bridge);  // 替代旧的 EventBusAgentProgressSink
 * }</pre>
 */
public final class SessionPoolBridge implements AgentProgressSink {

    private final SessionPool pool;
    private final String sessionId;

    /** streamId → nodeId 映射（LLM 流式 delta 追加用） */
    private final Map<String, String> streamNodes = new ConcurrentHashMap<>();

    public SessionPoolBridge(SessionPool pool, String sessionId) {
        this.pool = pool;
        this.sessionId = sessionId;
    }

    @Override
    public void onProgress(AgentProgressEvent event) {
        if (event == null) return;
        try {
            dispatch(event);
        } catch (Exception e) {
            // don't let bridge errors propagate
            pool.pushNode(sessionId, NodeType.SYSTEM, Map.of(
                    "message", "SessionPoolBridge error: " + e.getMessage(),
                    "error", e.getClass().getSimpleName()));
        }
    }

    private void dispatch(AgentProgressEvent event) {
        switch (event.phase()) {
            // ---- LLM 流式 ----
            case AgentProgressEvent.LLM_STREAM_STARTED -> {
                String streamId = event.meta().get("streamId");
                SessionNode node = pool.pushNode(sessionId, NodeType.LLM_STREAMING, newPayload());
                node.payload().put("streamId", streamId);
                node.payload().put("content", new StringBuilder());
                pool.transitionStatus(node.nodeId(), NodeStatus.PENDING);
                if (streamId != null) streamNodes.put(streamId, node.nodeId());
            }
            case AgentProgressEvent.LLM_CONTENT_DELTA -> {
                String streamId = event.meta().get("streamId");
                String nodeId = streamId != null ? streamNodes.get(streamId) : null;
                if (nodeId != null) {
                    SessionNode node = pool.nodeById(nodeId);
                    if (node != null && node.payload().get("status") != NodeStatus.STREAMING) {
                        pool.transitionStatus(nodeId, NodeStatus.STREAMING);
                    }
                    pool.appendContent(nodeId, event.detail());
                }
            }
            case AgentProgressEvent.LLM_REASONING_DELTA -> {
                String streamId = event.meta().get("streamId");
                String nodeId = streamId != null ? streamNodes.get(streamId) : null;
                if (nodeId != null) {
                    pool.updateNode(nodeId, "reasoning",
                            pool.nodeById(nodeId).payload()
                                    .getOrDefault("reasoning", "") + event.detail());
                }
            }
            case AgentProgressEvent.LLM_STREAM_COMPLETED -> {
                String streamId = event.meta().get("streamId");
                String nodeId = streamId != null ? streamNodes.remove(streamId) : null;
                if (nodeId != null) {
                    SessionNode node = pool.nodeById(nodeId);
                    if (node != null) {
                        // flush StringBuilder → String for downstream consumers
                        Object content = node.payload().get("content");
                        if (content instanceof StringBuilder sb) {
                            node.payload().put("content", sb.toString());
                        }
                        pool.transitionStatus(nodeId, NodeStatus.DONE);
                    }
                }
            }
            case AgentProgressEvent.LLM_STREAM_FAILED -> {
                String streamId = event.meta().get("streamId");
                String nodeId = streamId != null ? streamNodes.remove(streamId) : null;
                if (nodeId != null) {
                    pool.updateNode(nodeId, "error", event.detail());
                    pool.transitionStatus(nodeId, NodeStatus.ERROR);
                }
            }
            case AgentProgressEvent.LLM_TOOL_CALL_DELTA -> {
                // LLM 正在输出 tool call JSON — 可以作为 streaming 节点的 metadata 标记
            }

            // ---- 工具调用 ----
            case AgentProgressEvent.TOOL_SELECTED -> {
                String toolName = event.meta().get("tool");
                SessionNode node = pool.pushNode(sessionId, NodeType.TOOL_CALL, newPayload());
                node.payload().put("tool", toolName);
                pool.transitionStatus(node.nodeId(), NodeStatus.PENDING);
            }
            case AgentProgressEvent.TOOL_EXECUTING -> {
                // find the most recent TOOL_CALL node and mark it as executing
                markLatestToolCall(event);
            }
            case AgentProgressEvent.TOOL_SUCCESS -> {
                String toolName = event.meta().get("tool");
                markLatestToolCallDone(toolName, "success", event);
            }
            case AgentProgressEvent.TOOL_FAILED -> {
                String toolName = event.meta().get("tool");
                String error = event.meta().get("error");
                markLatestToolCallDone(toolName, "failed", event);
                if (error != null && !error.isBlank()) {
                    pool.pushNode(sessionId, NodeType.SYSTEM, Map.of(
                            "message", "工具失败: " + toolName,
                            "error", error));
                }
            }
            case AgentProgressEvent.AWAITING_TOOL_CONFIRMATION -> {
                // tool confirmation waiting — passive event, no node change needed
            }

            // ---- 控制事件 ----
            case AgentProgressEvent.FINISH_ACTION_ACCEPTED -> {
                pool.pushNode(sessionId, NodeType.SYSTEM,
                        Map.of("message", "finish_action 通过，本轮结束。"));
            }
            case AgentProgressEvent.FINISH_ACTION_REJECTED -> {
                String reason = event.meta().get("rejectReason");
                pool.pushNode(sessionId, NodeType.SYSTEM, Map.of(
                        "message", "finish_action 被拒绝: " + (reason != null ? reason : "")));
            }
            case AgentProgressEvent.ABORTED -> {
                pool.pushNode(sessionId, NodeType.SYSTEM, Map.of(
                        "message", "中止: " + event.detail()));
            }

            // ---- Agent 公开消息 ----
            case AgentProgressEvent.AGENT_PUBLIC_MESSAGE -> {
                SessionNode node = pool.pushNode(sessionId, NodeType.AGENT_MESSAGE, newPayload());
                node.payload().put("message", event.detail());
                String subType = event.meta().get("subType");
                if (subType != null) node.payload().put("subType", subType);
                String title = event.meta().get("title");
                if (title != null) node.payload().put("title", title);
                String body = event.meta().get("body");
                if (body != null) node.payload().put("body", body);
                pool.transitionStatus(node.nodeId(), NodeStatus.DONE);
            }

            // ---- 其他被动事件（不需创建节点） ----
            case AgentProgressEvent.CONTEXT_LOADED,
                 AgentProgressEvent.WAITING_LLM,
                 AgentProgressEvent.AWAITING_FINISH_ACTION,
                 AgentProgressEvent.PLAIN_ANSWER_WITHOUT_FINISH,
                 AgentProgressEvent.INVALID_BRACKET_INTENT -> {
                // 这些是调试/进度事件，不需要为每个创建节点
            }

            default -> {
                // 未识别的 phase，创建 SYSTEM 节点记录
                // (silently skip for now)
            }
        }
    }

    /** 标记最近一个 TOOL_CALL 节点为 done（成功或失败）。 */
    private void markLatestToolCallDone(String toolName, String result, AgentProgressEvent event) {
        var nodes = pool.getNodes(sessionId);
        for (int i = nodes.size() - 1; i >= 0; i--) {
            SessionNode n = nodes.get(i);
            if (n.type() == NodeType.TOOL_CALL
                    && n.payload().get("status") != NodeStatus.DONE
                    && n.payload().get("status") != NodeStatus.ERROR) {
                n.payload().put("result", result);
                n.payload().put("detail", event.detail());
                pool.transitionStatus(n.nodeId(),
                        "failed".equals(result) ? NodeStatus.ERROR : NodeStatus.DONE);
                return;
            }
        }
    }

    /** 标记最近一个 TOOL_CALL 节点为 executing。 */
    private void markLatestToolCall(AgentProgressEvent event) {
        var nodes = pool.getNodes(sessionId);
        for (int i = nodes.size() - 1; i >= 0; i--) {
            SessionNode n = nodes.get(i);
            if (n.type() == NodeType.TOOL_CALL) {
                pool.transitionStatus(n.nodeId(), NodeStatus.STREAMING);
                return;
            }
        }
    }

    private static Map<String, Object> newPayload() {
        return new ConcurrentHashMap<>();
    }
}
