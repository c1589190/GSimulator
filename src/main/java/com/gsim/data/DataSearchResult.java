package com.gsim.data;

/**
 * 数据搜索单条结果。
 */
public record DataSearchResult(
        String id,
        String type,
        String role,
        String name,
        String path,
        String snippet,
        double score
) {}
