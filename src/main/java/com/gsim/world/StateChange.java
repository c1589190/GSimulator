package com.gsim.world;

import java.util.List;

/**
 * 世界状态变更 — 推演结果导致的状态修改建议。
 */
public record StateChange(
        String id,
        String campaignId,
        String turnId,
        String targetType,         // faction, character, world, etc.
        String targetId,
        String field,
        String oldValue,
        String newValue,
        String reason,
        List<String> evidenceIds,
        double confidence
) {
    public StateChange {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("id must not be blank");
        }
        if (campaignId == null || campaignId.isBlank()) {
            throw new IllegalArgumentException("campaignId must not be blank");
        }
        if (turnId == null || turnId.isBlank()) {
            throw new IllegalArgumentException("turnId must not be blank");
        }
    }
}
