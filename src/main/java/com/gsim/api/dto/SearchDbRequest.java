package com.gsim.api.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * POST /api/searchdb 的请求体。
 */
public record SearchDbRequest(
        @JsonProperty("query") String query,
        @JsonProperty("topK") int topK,
        @JsonProperty("mode") String mode
) {
    public SearchDbRequest {
        if (query == null) query = "";
        if (topK <= 0) topK = 5;
        if (mode == null || mode.isBlank()) mode = "keyword";
    }
}
