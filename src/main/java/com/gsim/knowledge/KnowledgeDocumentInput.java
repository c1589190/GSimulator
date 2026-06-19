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
        @JsonProperty("metadata") Map<String, String> metadata,
        @JsonProperty("root_id") String rootId,
        @JsonProperty("branch_id") String branchId,
        @JsonProperty("revision_of") String revisionOf,
        @JsonProperty("target_key") String targetKey,
        @JsonProperty("change_type") String changeType
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
        if (rootId == null) {
            rootId = "";
        }
        if (branchId == null) {
            branchId = "";
        }
        if (revisionOf == null) {
            revisionOf = "";
        }
        if (targetKey == null) {
            targetKey = "";
        }
        if (changeType == null || changeType.isBlank()) {
            changeType = "created";
        }
    }

    /** 兼容旧构造器（无 branch metadata）。 */
    public KnowledgeDocumentInput(String title, String content, String collection,
                                  String sourceType, String sourceUri,
                                  Map<String, String> metadata) {
        this(title, content, collection, sourceType, sourceUri, metadata, "", "", "", "", "created");
    }
}
