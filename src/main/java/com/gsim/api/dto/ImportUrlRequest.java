package com.gsim.api.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * POST /api/import/url 的请求体。
 */
public record ImportUrlRequest(
        @JsonProperty("url") String url,
        @JsonProperty("fetchOnly") boolean fetchOnly,
        @JsonProperty("maxPages") int maxPages,
        @JsonProperty("depth") int depth,
        @JsonProperty("delayMs") int delayMs
) {
    public ImportUrlRequest {
        if (url == null) url = "";
        if (maxPages <= 0) maxPages = 3;
        if (depth <= 0) depth = 1;
        if (delayMs <= 0) delayMs = 1000;
    }
}
