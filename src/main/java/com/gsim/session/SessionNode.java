package com.gsim.session;

import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 会话节点 — SessionPool 中的统一交互单元。
 *
 * <p>Record 本身不可变（nodeId/sessionId/parentId/type），但 payload 是可变 Map，
 * 支持流式更新（LLM delta 追加到 content key）。
 */
public record SessionNode(
        String nodeId,
        String sessionId,
        String parentId,
        NodeType type,
        Instant createdAt,
        Map<String, Object> payload
) {

    public SessionNode {
        if (nodeId == null || nodeId.isBlank()) throw new IllegalArgumentException("nodeId must not be blank");
        if (sessionId == null || sessionId.isBlank()) throw new IllegalArgumentException("sessionId must not be blank");
        if (type == null) throw new IllegalArgumentException("type must not be null");
        if (createdAt == null) createdAt = Instant.now();
        if (payload == null) payload = new ConcurrentHashMap<>();
    }
}
