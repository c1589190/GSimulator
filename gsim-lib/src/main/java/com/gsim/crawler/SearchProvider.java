package com.gsim.crawler;

import java.util.List;

/**
 * 搜索提供者接口。
 */
public interface SearchProvider {
    /**
     * 根据查询条件搜索。
     *
     * @param query 搜索查询字符串
     * @param maxResults 最大返回结果数
     * @return 搜索结果列表
     */
    List<SearchResult> search(String query, int maxResults);
}
