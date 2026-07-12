package com.gsim.crawler;

/**
 * 搜索结果。
 */
public record SearchResult(
        String title,
        String url,
        String snippet,
        double relevanceScore
) {
}
