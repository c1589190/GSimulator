package com.gsim.world;

import java.util.List;
import java.util.Map;

/**
 * 角色档案 — 重要人物、统治者、将领、玩家角色。
 */
public record CharacterProfile(
        String id,
        String campaignId,
        String name,
        String title,
        String factionId,
        String description,
        Map<String, Object> attributes,
        List<String> notableActions,
        String status            // active, deceased, imprisoned, exiled, etc.
) {
}
