package com.gsim.crawler;

import java.util.List;

/**
 * 搜索计划 — ResearchAgent 生成的联网研究方案。
 */
public record SearchPlan(
        String reason,
        List<String> searchQuestions,
        List<String> keywords,
        List<String> factsToVerify,
        int maxSearchResults,
        int maxPagesToFetch
) {
}
