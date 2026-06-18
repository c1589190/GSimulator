package com.gsim.knowledge;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Upsert 操作结果。
 */
public record KnowledgeUpsertResult(
        boolean success,
        @JsonProperty("doc_id") String docId,
        @JsonProperty("chunks_created") int chunksCreated,
        @JsonProperty("embeddings_created") int embeddingsCreated,
        @JsonProperty("profile_id") String profileId,
        String status,
        String error
) {
    public static KnowledgeUpsertResult keywordOnly(String docId, int chunksCreated) {
        return new KnowledgeUpsertResult(true, docId, chunksCreated, 0, null, "KEYWORD_ONLY", "");
    }

    public static KnowledgeUpsertResult withEmbeddings(String docId, int chunksCreated,
                                                        int embeddingsCreated, String profileId) {
        return new KnowledgeUpsertResult(true, docId, chunksCreated, embeddingsCreated, profileId, "OK", "");
    }

    public static KnowledgeUpsertResult fail(String error) {
        return new KnowledgeUpsertResult(false, null, 0, 0, null, "FAILED", error);
    }
}
