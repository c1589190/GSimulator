package com.gsim.knowledge;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Delete 操作结果。
 */
public record KnowledgeDeleteResult(
        boolean success,
        @JsonProperty("doc_id") String docId,
        @JsonProperty("chunks_deleted") int chunksDeleted,
        @JsonProperty("embeddings_deleted") int embeddingsDeleted,
        String error
) {
    public static KnowledgeDeleteResult ok(String docId, int chunksDeleted, int embeddingsDeleted) {
        return new KnowledgeDeleteResult(true, docId, chunksDeleted, embeddingsDeleted, "");
    }

    public static KnowledgeDeleteResult notFound(String docId) {
        return new KnowledgeDeleteResult(false, docId, 0, 0,
                "DOCUMENT_NOT_FOUND: " + docId);
    }

    public static KnowledgeDeleteResult fail(String docId, String error) {
        return new KnowledgeDeleteResult(false, docId, 0, 0, error);
    }
}
