package com.gsim.knowledge;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.Map;

/**
 * 文档录入/更新输入 — Agent 通过 tool 传入。
 */
public record KnowledgeDocumentInput(
        String title,
        String content,
        String collection,
        @JsonProperty("source_type") String sourceType,
        @JsonProperty("source_uri") String sourceUri,
        @JsonProperty("metadata") Map<String, String> metadata
) {
    public KnowledgeDocumentInput {
        if (collection == null || collection.isBlank()) {
            collection = "default";
        }
        if (sourceType == null || sourceType.isBlank()) {
            sourceType = "agent_note";
        }
        if (sourceUri == null) {
            sourceUri = "";
        }
        if (metadata == null) {
            metadata = Map.of();
        }
    }
}
