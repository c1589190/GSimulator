package com.gsim.timeline;

import java.time.Instant;
import java.util.List;

/**
 * 时间线事件 — 推演中发生的重大事件。
 */
public record TimelineEvent(
        String id,
        String campaignId,
        String turnId,
        String date,
        String title,
        String description,
        List<String> actors,
        List<String> causes,
        List<String> effects,
        double confidence,
        List<String> sourceIds
) {
    public TimelineEvent {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("id must not be blank");
        }
        if (campaignId == null || campaignId.isBlank()) {
            throw new IllegalArgumentException("campaignId must not be blank");
        }
        if (turnId == null || turnId.isBlank()) {
            throw new IllegalArgumentException("turnId must not be blank");
        }
        if (title == null || title.isBlank()) {
            throw new IllegalArgumentException("title must not be blank");
        }
    }
}
