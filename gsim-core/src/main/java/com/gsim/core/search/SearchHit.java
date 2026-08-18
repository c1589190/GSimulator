package com.gsim.core.search;

/**
 * 搜索结果命中。
 *
 * @param key     命中的条目 key
 * @param snippet 首个命中 token 前后各 10 字符的摘要片段（边界已钳制）
 * @param score   匹配评分（query token 在条目文本中出现次数之和）
 * @param sortKey 条目排序键（透传）
 */
public record SearchHit(String key, String snippet, double score, long sortKey) {}
