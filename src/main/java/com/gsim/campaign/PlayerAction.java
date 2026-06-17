package com.gsim.campaign;

import java.time.Instant;
import java.util.List;

/**
 * 玩家行动 — 单个玩家在一个回合内递交的行动内容。
 */
public record PlayerAction(
        String id,
        String campaignId,
        String turnId,
        String playerName,
        String content,
        Instant createdAt,
        List<String> tags
) {
    public PlayerAction {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("id must not be blank");
        }
        if (campaignId == null || campaignId.isBlank()) {
            throw new IllegalArgumentException("campaignId must not be blank");
        }
        if (turnId == null || turnId.isBlank()) {
            throw new IllegalArgumentException("turnId must not be blank");
        }
        if (playerName == null || playerName.isBlank()) {
            throw new IllegalArgumentException("playerName must not be blank");
        }
        if (content == null || content.isBlank()) {
            throw new IllegalArgumentException("content must not be blank");
        }
        if (createdAt == null) {
            throw new IllegalArgumentException("createdAt must not be null");
        }
    }

    public static PlayerAction create(
            String id, String campaignId, String turnId,
            String playerName, String content, Instant now) {
        return new PlayerAction(id, campaignId, turnId, playerName, content, now, List.of());
    }
}
