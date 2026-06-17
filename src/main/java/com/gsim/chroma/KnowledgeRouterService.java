package com.gsim.chroma;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

/**
 * 知识路由服务 — 执行 RetrievalPlan 中的查询，汇总为 EvidenceBundle。
 */
public class KnowledgeRouterService {

    private static final Logger log = LoggerFactory.getLogger(KnowledgeRouterService.class);

    private final ChromaClient chromaClient;

    public KnowledgeRouterService(ChromaClient chromaClient) {
        this.chromaClient = chromaClient;
    }

    /**
     * 执行检索计划。
     */
    public EvidenceBundle execute(RetrievalPlan plan, String taskId) {
        if (!chromaClient.isAvailable()) {
            log.warn("ChromaDB is not available, returning empty evidence bundle");
            return new EvidenceBundle(taskId, List.of(), "ChromaDB unavailable");
        }

        List<EvidenceItem> allItems = new ArrayList<>();

        for (RetrievalQuery query : plan.queries()) {
            ChromaQueryRequest req = new ChromaQueryRequest(
                    query.collection(), query.query(),
                    query.metadataFilter(), query.topK());
            ChromaQueryResponse resp = chromaClient.query(req);

            for (ChromaQueryResponse.ChromaHit hit : resp.hits()) {
                EvidenceItem item = new EvidenceItem(
                        hit.id(),
                        String.valueOf(hit.metadata().getOrDefault("sourceType", "unknown")),
                        String.valueOf(hit.metadata().getOrDefault("id", hit.id())),
                        query.collection(),
                        String.valueOf(hit.metadata().getOrDefault("title", "")),
                        hit.document(),
                        null,
                        hit.score(),
                        Double.parseDouble(
                                String.valueOf(hit.metadata().getOrDefault("confidence", "0.5"))),
                        hit.metadata()
                );
                allItems.add(item);
            }
        }

        String summary = String.format("Retrieved %d evidence items from %d queries.",
                allItems.size(), plan.queries().size());
        return new EvidenceBundle(taskId, allItems, summary);
    }

    public boolean isAvailable() {
        return chromaClient.isAvailable();
    }
}
