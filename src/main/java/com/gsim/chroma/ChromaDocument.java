package com.gsim.chroma;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * ChromaDB 文档。
 */
public record ChromaDocument(
        String id,
        String campaignId,
        String turnId,
        String sourceType,
        String sourceId,
        String collection,
        String title,
        String text,
        List<String> tags,
        String author,
        Instant createdAt,
        Instant updatedAt,
        double confidence,
        int version,
        Map<String, Object> metadata
) {
}
