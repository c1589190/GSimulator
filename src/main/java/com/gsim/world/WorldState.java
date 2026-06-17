package com.gsim.world;

import java.time.Instant;
import java.util.Map;

/**
 * 世界状态 — 某个时刻的全局状态快照。
 */
public record WorldState(
        String campaignId,
        String turnId,
        Instant snapshotAt,
        Map<String, Object> fields
) {
}
