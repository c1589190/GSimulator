package com.gsim.world;

import java.util.List;
import java.util.Map;

/**
 * 派系/势力 — 国家、政党、军队、公司等。
 */
public record Faction(
        String id,
        String campaignId,
        String name,
        String type,          // nation, military, political_party, corporation, etc.
        String description,
        Map<String, Object> attributes,
        List<String> allies,
        List<String> rivals,
        List<String> memberIds
) {
}
