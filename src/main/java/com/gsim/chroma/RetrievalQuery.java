package com.gsim.chroma;

import java.util.Map;

/**
 * 单条检索查询。
 */
public record RetrievalQuery(
        String collection,
        String query,
        Map<String, String> metadataFilter,
        int topK,
        String purpose
) {
}
