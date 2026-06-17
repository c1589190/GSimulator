package com.gsim.chroma;

import java.util.Map;

/**
 * 证据条目 — 从知识库检索到的单条证据。
 */
public record EvidenceItem(
        String id,
        String sourceType,
        String sourceId,
        String collection,
        String title,
        String text,
        String url,
        double score,
        double confidence,
        Map<String, Object> metadata
) {
}
