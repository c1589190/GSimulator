package com.gsim.knowledge;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * 知识搜索结果。
 */
public record KnowledgeSearchResult(
        @JsonProperty("chunk_id") String chunkId,
        @JsonProperty("doc_id") String docId,
        String title,
        @JsonProperty("source_uri") String sourceUri,
        String collection,
        String snippet,
        @JsonProperty("vector_score") double vectorScore,
        @JsonProperty("keyword_score") double keywordScore,
        @JsonProperty("final_score") double finalScore,
        @JsonProperty("profile_id") String profileId,
        @JsonProperty("search_mode") String searchMode
) {}
