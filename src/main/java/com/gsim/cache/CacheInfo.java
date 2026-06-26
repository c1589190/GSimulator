package com.gsim.cache;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Cache 元信息 — 不加载消息体的轻量级 cache 摘要。
 *
 * <p>Cache 唯一 key = (worldId, sessionId)。
 * 对外按 (agentType, createdAt) 编排排序。
 */
public record CacheInfo(
        String agentName,         // e.g. "Orchestrator", "sim-1", "search-3"
        String agentType,         // "orchestrator", "sim", "search" (从 agentName 推断)
        String sessionId,         // 文件名，如 "Orchestrator_2026-06-26T10-30-00.json"
        String worldId,
        String nodeId,
        String createdAt,         // ISO timestamp
        int messageCount,
        String previousSessionId  // 链式缓存关联（可以为 null）
) {
    /** 从 agentName 推断 agentType。 */
    public static String inferType(String agentName) {
        if (agentName == null) return "unknown";
        // agentName 格式: "orchestrator" 或 "sim-1" / "search-3"
        String lower = agentName.toLowerCase();
        if (lower.startsWith("orchestrator")) return "orchestrator";
        if (lower.startsWith("sim")) return "sim";
        if (lower.startsWith("search")) return "search";
        // 其他情况取第一个连字符前的部分
        int dash = agentName.indexOf('-');
        return dash > 0 ? agentName.substring(0, dash) : agentName;
    }

    /** 从 CacheSession 提取元信息（不加载消息体）。 */
    public static CacheInfo fromSession(CacheSession session) {
        return new CacheInfo(
                session.agentName(),
                inferType(session.agentName()),
                session.sessionId(),
                session.worldId(),
                session.nodeId(),
                session.createdAt(),
                session.messageCount(),
                session.previousSessionId()
        );
    }
}
