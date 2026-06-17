package com.gsim.campaign;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.Instant;
import java.util.List;

/**
 * 战役/游戏局 — 顶层容器。
 */
public record Campaign(
        String campaignId,
        String name,
        Instant createdAt,
        String currentTurnId,
        List<String> turnIds
) {
    public Campaign {
        if (campaignId == null || campaignId.isBlank()) {
            throw new IllegalArgumentException("campaignId must not be blank");
        }
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("name must not be blank");
        }
        if (createdAt == null) {
            throw new IllegalArgumentException("createdAt must not be null");
        }
    }

    /**
     * 创建一个新的默认 campaign。
     */
    public static Campaign createDefault(String campaignId, Instant now) {
        return new Campaign(campaignId, "default-campaign", now, null, List.of());
    }

    /**
     * 设置当前回合。
     */
    public Campaign withCurrentTurnId(String newTurnId) {
        return new Campaign(campaignId, name, createdAt, newTurnId, turnIds);
    }

    /**
     * 追加一个 turn ID。
     */
    public Campaign withAddedTurnId(String turnId) {
        var newList = new java.util.ArrayList<>(turnIds);
        if (!newList.contains(turnId)) {
            newList.add(turnId);
        }
        return new Campaign(campaignId, name, createdAt, currentTurnId, List.copyOf(newList));
    }
}
