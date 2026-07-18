package com.gsim.api.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * POST /api/import/url 的请求体。
 *
 * <p>包含 URL 导入所需的参数，如抓取深度、页面数和请求延迟。
 * 各字段在规范化构造器中设有合理的默认值。
 *
 * @param url       要导入的 URL 地址
 * @param fetchOnly 是否仅抓取不导入
 * @param maxPages  最大抓取页数，默认为 3
 * @param depth     递归抓取深度，默认为 1
 * @param delayMs   请求间隔延迟（毫秒），默认为 1000
 */
public record ImportUrlRequest(
        @JsonProperty("url") String url,
        @JsonProperty("fetchOnly") boolean fetchOnly,
        @JsonProperty("maxPages") int maxPages,
        @JsonProperty("depth") int depth,
        @JsonProperty("delayMs") int delayMs) {
    public ImportUrlRequest {
        if (url == null) url = "";
        if (maxPages <= 0) maxPages = 3;
        if (depth <= 0) depth = 1;
        if (delayMs <= 0) delayMs = 1000;
    }
}
