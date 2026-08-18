package com.gsim.core.search;

/**
 * 待搜索的文本条目。
 *
 * @param text    搜索目标全文
 * @param key     条目唯一标识（返回给调用方用于定位元素）
 * @param sortKey 用于 {@link SearchOptions.SortMode#SORT_KEY_ASC}/{@link SearchOptions.SortMode#SORT_KEY_DESC}
 *     排序的稳定键
 */
public record SearchEntry(String text, String key, long sortKey) {}
