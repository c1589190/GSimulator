package com.gsim.chroma;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 假的 ChromaDB 客户端，用于测试和离线开发。
 */
public class FakeChromaClient implements ChromaClient {

    private boolean available = true;
    private final Map<String, List<ChromaDocument>> store = new ConcurrentHashMap<>();

    public FakeChromaClient() {
        // 预创建所有 collection
        for (String coll : ChromaConfig.ALL_COLLECTIONS) {
            store.put(coll, new ArrayList<>());
        }
    }

    public void setAvailable(boolean available) {
        this.available = available;
    }

    @Override
    public boolean isAvailable() {
        return available;
    }

    @Override
    public ChromaQueryResponse query(ChromaQueryRequest request) {
        if (!available) {
            return new ChromaQueryResponse(request.collection(), request.queryText(), List.of());
        }

        List<ChromaDocument> docs = store.getOrDefault(request.collection(), List.of());
        // 简单文本匹配（模拟语义搜索）
        int topK = Math.min(request.topK(), docs.size());
        List<ChromaQueryResponse.ChromaHit> hits = new ArrayList<>();

        for (int i = 0; i < topK && i < docs.size(); i++) {
            ChromaDocument doc = docs.get(i);
            // 简单包含匹配
            double score = 0.0;
            String queryLower = request.queryText().toLowerCase();
            if (doc.text() != null && doc.text().toLowerCase().contains(queryLower)) {
                score = 0.8;
            } else if (doc.title() != null && doc.title().toLowerCase().contains(queryLower)) {
                score = 0.6;
            }

            hits.add(new ChromaQueryResponse.ChromaHit(
                    doc.id(),
                    doc.text(),
                    Map.of(
                            "id", doc.id(),
                            "title", doc.title() != null ? doc.title() : "",
                            "collection", doc.collection(),
                            "sourceType", doc.sourceType(),
                            "confidence", String.valueOf(doc.confidence())
                    ),
                    score
            ));
        }

        return new ChromaQueryResponse(request.collection(), request.queryText(), hits);
    }

    @Override
    public int addDocuments(String collection, List<ChromaDocument> documents) {
        if (!available) {
            return 0;
        }
        List<ChromaDocument> coll = store.computeIfAbsent(collection, k -> new ArrayList<>());
        coll.addAll(documents);
        return documents.size();
    }

    @Override
    public List<String> listCollections() {
        return new ArrayList<>(store.keySet());
    }

    /**
     * 获取 store 用于测试验证。
     */
    public Map<String, List<ChromaDocument>> getStore() {
        return store;
    }
}
