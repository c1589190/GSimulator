package com.gsim.cache;

import java.util.Locale;

/**
 * Cache 元信息 — 不加载消息体的轻量级 cache 摘要。
 *
 * <p>Cache 唯一 key = (worldId, sessionId)。
 * 对外按 (agentType, createdAt) 编排排序。
 */
public record CacheInfo(
        String agentName, // e.g. "Orchestrator", "sim-1", "search-3"
        String agentType, // "orchestrator", "sim", "search" (从 agentName 推断)
        String sessionId, // 文件名，如 "Orchestrator_2026-06-26T10-30-00.json"
        String worldId,
        String nodeId,
        String createdAt, // ISO timestamp
        int messageCount,
        String previousSessionId, // 链式缓存关联（可以为 null）
        String firstUserMsg // 首条 user 消息摘要（轻量索引时提取）
        ) {
    /**
     * 从 agentName 推断 agentType。
     *
     * @param agentName Agent 名称（如 "orchestrator", "sim-1", "search-3"）
     * @return 推断出的 agentType，无法识别时返回 "unknown"
     */
    public static String inferType(String agentName) {
        if (agentName == null) return "unknown";
        // agentName 格式: "orchestrator" 或 "sim-1" / "search-3"
        String lower = agentName.toLowerCase(Locale.ROOT);
        if (lower.startsWith("orchestrator")) return "orchestrator";
        if (lower.startsWith("sim")) return "sim";
        if (lower.startsWith("search")) return "search";
        // 其他情况取第一个连字符前的部分
        int dash = agentName.indexOf('-');
        return dash > 0 ? agentName.substring(0, dash) : agentName;
    }

    /**
     * 从 CacheSession 提取元信息（不加载消息体）。
     *
     * @param session CacheSession 实例
     * @return 提取出的 CacheInfo 记录
     */
    public static CacheInfo fromSession(CacheSession session) {
        return new CacheInfo(
                session.agentName(),
                inferType(session.agentName()),
                session.sessionId(),
                session.worldId(),
                session.nodeId(),
                session.createdAt(),
                session.messageCount(),
                session.previousSessionId(),
                null // firstUserMsg — 需要解析消息列表
                );
    }
}
