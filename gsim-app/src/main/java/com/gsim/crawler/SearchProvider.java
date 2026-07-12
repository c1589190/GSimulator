package com.gsim.crawler;

import java.util.List;

/**
 * 搜索提供者接口。
 */
public interface SearchProvider {
    List<SearchResult> search(String query, int maxResults);
}
