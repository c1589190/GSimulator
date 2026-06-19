package com.gsim.knowledge;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * 知识文档 — 一个完整的入库知识条目。
 */
public record KnowledgeDocument(
        @JsonProperty("doc_id") String docId,
        @JsonProperty("source_type") String sourceType,
        @JsonProperty("source_uri") String sourceUri,
        String title,
        String collection,
        String content,
        @JsonProperty("metadata_json") String metadataJson,
        @JsonProperty("content_hash") String contentHash,
        @JsonProperty("created_at") String createdAt,
        @JsonProperty("updated_at") String updatedAt,
        @JsonProperty("root_id") String rootId,
        @JsonProperty("branch_id") String branchId,
        @JsonProperty("revision_of") String revisionOf,
        @JsonProperty("target_key") String targetKey,
        @JsonProperty("change_type") String changeType
) {}
