package com.gsim.chroma;

import java.util.Map;

/**
 * ChromaDB 查询请求。
 */
public record ChromaQueryRequest(
        String collection,
        String queryText,
        Map<String, String> metadataFilter,
        int topK
) {
}
