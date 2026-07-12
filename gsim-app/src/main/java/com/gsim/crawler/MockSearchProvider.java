package com.gsim.crawler;

import java.util.List;

/**
 * Mock 搜索提供者 — 用于测试。
 */
public class MockSearchProvider implements SearchProvider {

    private List<SearchResult> mockResults = List.of();

    public void setMockResults(List<SearchResult> results) {
        this.mockResults = results;
    }

    @Override
    public List<SearchResult> search(String query, int maxResults) {
        int limit = Math.min(maxResults, mockResults.size());
        return mockResults.subList(0, limit);
    }
}
