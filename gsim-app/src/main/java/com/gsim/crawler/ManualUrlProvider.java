package com.gsim.crawler;

import java.util.List;

/**
 * 手动 URL 提供者 — 允许用户手动指定要抓取的 URL。
 */
public class ManualUrlProvider implements SearchProvider {

    private final List<String> urls;

    public ManualUrlProvider(List<String> urls) {
        this.urls = urls;
    }

    @Override
    public List<SearchResult> search(String query, int maxResults) {
        return urls.stream()
                .limit(maxResults)
                .map(url -> new SearchResult(url, url, "Manual URL", 1.0))
                .toList();
    }
}
