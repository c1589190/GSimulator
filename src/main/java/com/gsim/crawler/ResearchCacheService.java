package com.gsim.crawler;

import com.gsim.chroma.ChromaClient;
import com.gsim.chroma.ChromaDocument;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * 研究缓存服务 — 将 ResearchDocument 写入 ChromaDB research_cache collection。
 */
public class ResearchCacheService {

    private final ChromaClient chromaClient;

    public ResearchCacheService(ChromaClient chromaClient) {
        this.chromaClient = chromaClient;
    }

    /**
     * 缓存研究文档到 ChromaDB。
     */
    public int cacheDocuments(List<ResearchDocument> docs, String campaignId, String turnId) {
        if (!chromaClient.isAvailable() || docs.isEmpty()) {
            return 0;
        }

        List<ChromaDocument> chromaDocs = docs.stream()
                .map(d -> new ChromaDocument(
                        d.id(),
                        campaignId,
                        turnId,
                        "web_research",
                        d.url() != null ? d.url() : d.id(),
                        "research_cache",
                        d.title(),
                        d.summary() != null ? d.summary() : d.cleanedText(),
                        d.tags(),
                        "ResearchAgent",
                        d.fetchedAt(),
                        Instant.now(),
                        d.credibility(),
                        1,
                        Map.of("url", d.url() != null ? d.url() : "")
                ))
                .toList();

        return chromaClient.addDocuments("research_cache", chromaDocs);
    }
}
