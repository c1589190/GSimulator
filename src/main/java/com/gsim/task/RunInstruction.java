package com.gsim.task;

import java.time.Instant;

/**
 * 主持人强制要求 — 在 /run 时附带的自定义指令。
 */
public record RunInstruction(
        String campaignId,
        String turnId,
        String rawText,
        Instant createdAt
) {
    public RunInstruction {
        if (campaignId == null || campaignId.isBlank()) {
            throw new IllegalArgumentException("campaignId must not be blank");
        }
        if (turnId == null || turnId.isBlank()) {
            throw new IllegalArgumentException("turnId must not be blank");
        }
        if (rawText == null || rawText.isBlank()) {
            throw new IllegalArgumentException("rawText must not be blank");
        }
        if (createdAt == null) {
            throw new IllegalArgumentException("createdAt must not be null");
        }
    }
}
