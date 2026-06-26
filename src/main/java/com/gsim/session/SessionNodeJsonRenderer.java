package com.gsim.session;

import com.gsim.util.JsonUtils;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 将 SessionNode 序列化为 JSON-friendly Map，用于 WebSocket JSON 协议。
 *
 * <p>处理 StringBuilder → String 转换（流式 content），
 * 以及 payload 中各标准 key 的规范化输出。
 */
public final class SessionNodeJsonRenderer {

    private SessionNodeJsonRenderer() {}

    /** 将一个节点渲染为 JSON Map。 */
    public static Map<String, Object> render(SessionNode node) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("nodeId", node.nodeId());
        out.put("sessionId", node.sessionId());
        out.put("parentId", node.parentId());
        out.put("type", node.type().name());
        out.put("createdAt", node.createdAt().toString());

        // Payload — resolve StringBuilder to String
        Map<String, Object> pl = new LinkedHashMap<>();
        for (var entry : node.payload().entrySet()) {
            Object val = entry.getValue();
            pl.put(entry.getKey(), val instanceof StringBuilder sb ? sb.toString() : val);
        }
        out.put("payload", pl);

        // Convenience: status from payload or explicit field
        Object status = node.payload().get("status");
        out.put("status", status != null ? status.toString() : null);

        return out;
    }

    /** 渲染节点更新事件（单 key 变更）。 */
    public static Map<String, Object> renderUpdate(String nodeId, String key, Object newValue) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("nodeId", nodeId);
        out.put("key", key);
        out.put("value", newValue instanceof StringBuilder sb ? sb.toString() : newValue);
        return out;
    }

    /** 渲染状态变更事件。 */
    public static Map<String, Object> renderStatusChange(String nodeId, NodeStatus oldStatus, NodeStatus newStatus) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("nodeId", nodeId);
        out.put("oldStatus", oldStatus.name());
        out.put("newStatus", newStatus.name());
        return out;
    }

    /** 渲染历史回放：所有节点列表。 */
    public static Map<String, Object> renderHistory(List<SessionNode> nodes) {
        List<Map<String, Object>> rendered = nodes.stream()
                .map(SessionNodeJsonRenderer::render)
                .toList();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("nodes", rendered);
        return out;
    }

    /** 将渲染结果序列化为紧凑 JSON 字符串。 */
    public static String toJson(Map<String, Object> rendered) {
        return JsonUtils.toJsonCompact(rendered);
    }

    /** 构建完整的 WebSocket JSON 消息帧。 */
    public static String buildMessage(String event, Map<String, Object> data) {
        Map<String, Object> frame = new LinkedHashMap<>();
        frame.put("event", event);
        frame.putAll(data);
        return JsonUtils.toJsonCompact(frame);
    }
}
