package com.gsim.crawler;

import java.time.Instant;
import java.util.List;

/**
 * 研究文档 — ResearchAgent 抓取和总结的外部资料。
 */
public record ResearchDocument(
        String id,
        String title,
        String url,
        Instant fetchedAt,
        String rawText,
        String cleanedText,
        String summary,
        double credibility,
        List<String> tags
) {
}
