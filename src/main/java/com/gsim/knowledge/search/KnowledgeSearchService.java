package com.gsim.knowledge.search;

import com.gsim.knowledge.KnowledgeChunk;
import com.gsim.knowledge.KnowledgeSearchResponse;
import com.gsim.knowledge.KnowledgeSearchResult;
import com.gsim.knowledge.embed.EmbeddingModel;
import com.gsim.knowledge.embed.EmbeddingProfile;
import com.gsim.knowledge.embed.EmbeddingProfileManager;
import com.gsim.knowledge.embed.EmbeddingVector;
import com.gsim.knowledge.embed.VectorCodec;
import com.gsim.knowledge.store.KnowledgeStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.stream.Collectors;

/**
 * 知识搜索服务 — 协调 keyword 和 semantic 搜索。
 * 语义搜索在此层完成：验证 profile、生成 query vector、计算 cosine 相似度。
 */
public class KnowledgeSearchService {

    private static final Logger log = LoggerFactory.getLogger(KnowledgeSearchService.class);

    private final KnowledgeStore store;
    private final EmbeddingProfileManager profileManager;

    public KnowledgeSearchService(KnowledgeStore store, EmbeddingProfileManager profileManager) {
        this.store = store;
        this.profileManager = profileManager;
    }

    /**
     * 语义检索 — 需要 active embedding profile。
     */
    public KnowledgeSearchResponse semanticSearch(String query, String collection, int topK) {
        // 1. 检查 active profile
        Optional<EmbeddingProfile> activeProfile = profileManager.getActiveProfile();
        if (activeProfile.isEmpty()) {
            return KnowledgeSearchResponse.error("NO_ACTIVE_EMBEDDING_PROFILE",
                    "未配置 active embedding profile。请使用 keyword_search 或配置 embedding。");
        }
        EmbeddingProfile profile = activeProfile.get();

        // 2. 检查 embedding model 可用
        EmbeddingModel model = profileManager.getEmbeddingModel();
        if (model == null || !model.isAvailable()) {
            return KnowledgeSearchResponse.error("EMBEDDING_PROVIDER_UNAVAILABLE",
                    "Embedding model 不可用（profile: " + profile.profileId() + "）。");
        }

        // 3. 检查 collection 是否有该 profile 的 embeddings
        List<KnowledgeStore.ChunkEmbeddingRow> rows = store.getChunkEmbeddings(
                collection, profile.profileId());
        if (rows.isEmpty()) {
            return KnowledgeSearchResponse.error("NO_EMBEDDINGS_FOR_PROFILE",
                    "Collection '" + collection + "' 没有 profile '" + profile.profileId()
                    + "' 的 embeddings。请使用 knowledge_embed_missing 或 keyword_search。");
        }

        // 4. 生成 query embedding
        EmbeddingVector queryVector;
        try {
            queryVector = model.embed(query);
        } catch (Exception e) {
            return KnowledgeSearchResponse.error("EMBEDDING_PROVIDER_UNAVAILABLE",
                    "生成 query embedding 失败: " + e.getMessage());
        }

        // 5. 验证 profile 匹配
        if (!queryVector.profileId().equals(profile.profileId())) {
            return KnowledgeSearchResponse.error("PROFILE_MISMATCH",
                    "Query vector profile " + queryVector.profileId()
                    + " 与 active profile " + profile.profileId() + " 不匹配。");
        }

        // 6. 计算 cosine 相似度
        List<KnowledgeSearchResult> results = new ArrayList<>();
        for (KnowledgeStore.ChunkEmbeddingRow row : rows) {
            try {
                float[] storedVec = VectorCodec.decodeFloat32(row.vectorBlob(), row.dimensions());
                EmbeddingVector storedVector = new EmbeddingVector(storedVec, row.dimensions(),
                        row.profileId());
                double sim = queryVector.cosineSimilarity(storedVector);

                Optional<KnowledgeChunk> chunk = store.getChunk(row.chunkId());
                if (chunk.isPresent()) {
                    KnowledgeChunk c = chunk.get();
                    String snippet = c.text().length() > 300
                            ? c.text().substring(0, 300) + "..." : c.text();
                    // 获取 sourceUri
                    String sourceUri = store.getDocument(c.docId())
                            .map(d -> d.sourceUri()).orElse(null);
                    results.add(new KnowledgeSearchResult(
                            c.chunkId(), c.docId(), c.title(), sourceUri, c.collection(),
                            snippet, sim, 0.0, sim, profile.profileId(), "semantic"));
                }
            } catch (Exception e) {
                log.warn("Failed to decode vector for chunk {}: {}", row.chunkId(), e.getMessage());
            }
        }

        // 7. 按 similarity 降序排序，取 topK
        results.sort((a, b) -> Double.compare(b.finalScore(), a.finalScore()));
        if (results.size() > topK) {
            results = results.subList(0, topK);
        }

        return KnowledgeSearchResponse.ok(results);
    }

    /**
     * Hybrid 搜索：semantic + keyword 混合。
     */
    public List<KnowledgeSearchResult> hybridSearch(String query, String collection, int topK) {
        List<KnowledgeSearchResult> results = new ArrayList<>();

        KnowledgeSearchResponse semanticResp = semanticSearch(query, collection, topK);
        if (semanticResp.success() && semanticResp.items() != null) {
            results.addAll(semanticResp.items());
        }

        if (results.size() < topK) {
            int remaining = topK - results.size();
            List<KnowledgeSearchResult> keywordResults = store.searchKeyword(query, collection, remaining);
            Set<String> seen = results.stream().map(KnowledgeSearchResult::chunkId)
                    .collect(Collectors.toSet());
            for (KnowledgeSearchResult kr : keywordResults) {
                if (!seen.contains(kr.chunkId())) {
                    results.add(kr);
                    seen.add(kr.chunkId());
                }
            }
        }

        return results;
    }
}
