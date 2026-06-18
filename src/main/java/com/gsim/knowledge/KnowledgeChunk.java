package com.gsim.knowledge;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * 知识块 — 文档被切分后的片段。
 */
public record KnowledgeChunk(
        @JsonProperty("chunk_id") String chunkId,
        @JsonProperty("doc_id") String docId,
        String collection,
        String title,
        String text,
        @JsonProperty("chunk_index") int chunkIndex,
        @JsonProperty("start_char") int startChar,
        @JsonProperty("end_char") int endChar,
        @JsonProperty("content_hash") String contentHash,
        @JsonProperty("metadata_json") String metadataJson,
        @JsonProperty("created_at") String createdAt,
        @JsonProperty("updated_at") String updatedAt
) {}
