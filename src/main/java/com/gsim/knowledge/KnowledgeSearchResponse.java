package com.gsim.knowledge;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/**
 * 知识搜索响应 — 可以是成功列表或结构化错误。
 */
public record KnowledgeSearchResponse(
        boolean success,
        @JsonProperty("error_code") String errorCode,
        String error,
        List<KnowledgeSearchResult> items
) {
    public static KnowledgeSearchResponse ok(List<KnowledgeSearchResult> items) {
        return new KnowledgeSearchResponse(true, null, null,
                items != null ? items : List.of());
    }

    public static KnowledgeSearchResponse error(String code, String message) {
        return new KnowledgeSearchResponse(false, code, message, List.of());
    }
}
