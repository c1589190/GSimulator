package com.gsim.chroma;

import java.util.List;
import java.util.Map;

/**
 * ChromaDB 查询响应 — 包含匹配的文档列表。
 */
public record ChromaQueryResponse(
        String collection,
        String queryText,
        List<ChromaHit> hits
) {
    public record ChromaHit(
            String id,
            String document,
            Map<String, Object> metadata,
            double score
    ) {
    }
}
