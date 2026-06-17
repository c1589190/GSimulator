package com.gsim.campaign;

import java.time.Instant;

/**
 * 回合 — 一个推演结算周期。
 */
public record Turn(
        String campaignId,
        String turnId,
        int index,
        TurnStatus status,
        Instant createdAt,
        Instant resolvedAt
) {
    public Turn {
        if (campaignId == null || campaignId.isBlank()) {
            throw new IllegalArgumentException("campaignId must not be blank");
        }
        if (turnId == null || turnId.isBlank()) {
            throw new IllegalArgumentException("turnId must not be blank");
        }
        if (index < 0) {
            throw new IllegalArgumentException("index must be >= 0");
        }
        if (status == null) {
            throw new IllegalArgumentException("status must not be null");
        }
        if (createdAt == null) {
            throw new IllegalArgumentException("createdAt must not be null");
        }
    }

    public static Turn create(String campaignId, String turnId, int index, Instant now) {
        return new Turn(campaignId, turnId, index, TurnStatus.OPEN, now, null);
    }

    public Turn resolved(Instant resolvedAt) {
        return new Turn(campaignId, turnId, index, TurnStatus.RESOLVED, createdAt, resolvedAt);
    }

    public boolean isOpen() {
        return status == TurnStatus.OPEN;
    }
}
