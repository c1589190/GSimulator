package com.gsim.knowledge;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * KnowledgeStore 状态摘要。
 */
public record KnowledgeStoreStatus(
        @JsonProperty("db_path") String dbPath,
        @JsonProperty("document_count") int documentCount,
        @JsonProperty("chunk_count") int chunkCount,
        @JsonProperty("active_embedding_profile_id") String activeEmbeddingProfileId,
        @JsonProperty("embedding_profiles_count") int embeddingProfilesCount,
        @JsonProperty("chunk_embeddings_count") int chunkEmbeddingsCount,
        @JsonProperty("fts_available") boolean ftsAvailable,
        @JsonProperty("version") String version,
        @JsonProperty("default_collection") String defaultCollection
) {}
