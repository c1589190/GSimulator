package com.gsim.agentsmanager;

import java.time.Instant;

/**
 * AgentInstance -- Agent 运行时实例，HTTP API 可见的 Agent 元数据。
 *
 * <p>主 Agent 和 SubAgent 仅在 parentInstanceId 字段上区分：
 * 主 Agent 的 parentInstanceId 为 null，SubAgent 指向父 Agent 的 instanceId。
 *
 * @param instanceId        Agent 实例唯一标识
 * @param configId          引用的 Agent 配置 ID
 * @param sessionId         关联的 session ID
 * @param taskId            关联的 task ID
 * @param cacheId           对话缓存 ID
 * @param parentInstanceId  父 Agent 实例 ID（主 Agent 为 null）
 * @param prompt            初始任务指令
 * @param status            当前运行状态
 * @param createdAt         创建时间戳
 * @param finishedAt        完成时间戳（未完成时为 null）
 * @param error             错误信息（失败时不为 null）
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
        String error) {
    /**
     * 创建状态已更新的副本。
     *
     * @param newStatus  新状态
     * @param finishedAt 完成时间
     * @param error      错误信息
     * @return 更新了状态的新 AgentInstance 实例
     */
    public AgentInstance withStatus(AgentStatus newStatus, Instant finishedAt, String error) {
        return new AgentInstance(
                instanceId,
                configId,
                sessionId,
                taskId,
                cacheId,
                parentInstanceId,
                prompt,
                newStatus,
                createdAt,
                finishedAt,
                error);
    }

    /**
     * 创建仅状态更新的简化副本。
     *
     * @param newStatus 新状态
     * @return 更新了状态的新 AgentInstance 实例
     */
    public AgentInstance withStatus(AgentStatus newStatus) {
        return withStatus(newStatus, null, null);
    }
}
