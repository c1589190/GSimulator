package com.gsim.task;

import com.gsim.campaign.PlayerAction;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * 任务上下文 — 封装一次任务执行所需的全部信息。
 */
public record TaskContext(
        String taskId,
        String campaignId,
        String turnId,
        String userInstruction,
        TaskType taskType,
        List<PlayerAction> playerActions,
        Map<String, Object> options,
        Instant createdAt
) {
    public TaskContext {
        if (taskId == null || taskId.isBlank()) {
            throw new IllegalArgumentException("taskId must not be blank");
        }
        if (campaignId == null || campaignId.isBlank()) {
            throw new IllegalArgumentException("campaignId must not be blank");
        }
        if (turnId == null || turnId.isBlank()) {
            throw new IllegalArgumentException("turnId must not be blank");
        }
        if (taskType == null) {
            throw new IllegalArgumentException("taskType must not be null");
        }
        if (createdAt == null) {
            throw new IllegalArgumentException("createdAt must not be null");
        }
    }

    public boolean hasPlayerActions() {
        return playerActions != null && !playerActions.isEmpty();
    }
}
