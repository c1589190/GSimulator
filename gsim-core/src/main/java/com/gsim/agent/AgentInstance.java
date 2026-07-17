package com.gsim.agent;

import java.time.Instant;

/**
 * Agent 运行时实例 — HTTP API 可见的 Agent 元数据。
 *
 * <p>主 Agent 和 SubAgent 仅在 parentInstanceId 字段上区分：
 * 主 Agent 的 parentInstanceId 为 null，SubAgent 指向父 Agent 的 instanceId。
 */
public record AgentInstance(
        String instanceId,
        String configId,
        String sessionId,
        String taskId,
        String cacheId,
        String parentInstanceId,
        String prompt,
        AgentStatus status,
        Instant createdAt,
        Instant finishedAt,
        String error
) {
    public AgentInstance withStatus(AgentStatus newStatus, Instant finishedAt, String error) {
        return new AgentInstance(instanceId, configId, sessionId, taskId, cacheId,
                parentInstanceId, prompt, newStatus, createdAt, finishedAt, error);
    }

    public AgentInstance withStatus(AgentStatus newStatus) {
        return withStatus(newStatus, null, null);
    }
}
