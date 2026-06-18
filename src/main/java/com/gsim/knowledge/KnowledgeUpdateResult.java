package com.gsim.knowledge;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Update 操作结果。
 */
public record KnowledgeUpdateResult(
        boolean success,
        @JsonProperty("doc_id") String docId,
        @JsonProperty("old_chunks_deleted") int oldChunksDeleted,
        @JsonProperty("old_embeddings_deleted") int oldEmbeddingsDeleted,
        @JsonProperty("new_chunks_created") int newChunksCreated,
        @JsonProperty("new_embeddings_created") int newEmbeddingsCreated,
        @JsonProperty("profile_id") String profileId,
        String status,
        String error
) {
    public static KnowledgeUpdateResult keywordOnly(String docId, int oldChunksDeleted,
                                                     int newChunksCreated) {
        return new KnowledgeUpdateResult(true, docId, oldChunksDeleted, 0,
                newChunksCreated, 0, null, "KEYWORD_ONLY", "");
    }

    public static KnowledgeUpdateResult keywordOnly(String docId, int oldChunksDeleted,
                                                     int oldEmbeddingsDeleted,
                                                     int newChunksCreated) {
        return new KnowledgeUpdateResult(true, docId, oldChunksDeleted, oldEmbeddingsDeleted,
                newChunksCreated, 0, null, "KEYWORD_ONLY", "");
    }

    public static KnowledgeUpdateResult withEmbeddings(String docId, int oldChunksDeleted,
                                                        int oldEmbeddingsDeleted,
                                                        int newChunksCreated,
                                                        int newEmbeddingsCreated, String profileId) {
        return new KnowledgeUpdateResult(true, docId, oldChunksDeleted, oldEmbeddingsDeleted,
                newChunksCreated, newEmbeddingsCreated, profileId, "OK", "");
    }

    public static KnowledgeUpdateResult fail(String docId, String error) {
        return new KnowledgeUpdateResult(false, docId, 0, 0, 0, 0, null, "FAILED", error);
    }

    public static KnowledgeUpdateResult notFound(String docId) {
        return new KnowledgeUpdateResult(false, docId, 0, 0, 0, 0, null,
                "DOCUMENT_NOT_FOUND", "Document not found: " + docId);
    }
}
