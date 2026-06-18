package com.gsim.knowledge.store;

import com.gsim.knowledge.*;
import com.gsim.knowledge.embed.EmbeddingProfile;
import com.gsim.knowledge.embed.EmbeddingVector;

import java.util.List;
import java.util.Optional;

/**
 * 知识库存储抽象接口。
 * 语义检索（向量搜索）放在 KnowledgeSearchService，Store 只负责存取和 keyword query。
 */
public interface KnowledgeStore {

    /** 录入新文档（含 chunking）。 */
    KnowledgeUpsertResult upsert(KnowledgeDocumentInput input);

    /** 更新已有文档（删除旧 chunks/embeddings，重建）。 */
    KnowledgeUpdateResult update(String docId, KnowledgeDocumentInput input);

    /** 删除文档及关联的 chunks/fts/embeddings。 */
    KnowledgeDeleteResult delete(String docId);

    /** 按 docId 获取完整文档。 */
    Optional<KnowledgeDocument> getDocument(String docId);

    /** 按 chunkId 获取单个 chunk。 */
    Optional<KnowledgeChunk> getChunk(String chunkId);

    /** 关键词检索（FTS5 + LIKE），永远可用。 */
    List<KnowledgeSearchResult> searchKeyword(String query, String collection, int topK);

    /** 获取指定 collection 的所有 chunks 及其 embeddings（用于语义搜索）。 */
    List<ChunkEmbeddingRow> getChunkEmbeddings(String collection, String profileId);

    /** 批量写入 chunk embeddings。 */
    int writeEmbeddings(List<ChunkEmbeddingRow> rows);

    /** 查找缺少指定 profile embedding 的 chunk（全局，全 collection 扫描）。 */
    List<String> findChunksMissingEmbedding(String collection, String profileId);

    /** 查找指定文档缺少 embedding 的 chunk（仅限当前 doc，不扫全库）。 */
    List<String> findChunksMissingEmbeddingForDoc(String docId, String profileId);

    /** 获取 store 状态摘要。 */
    KnowledgeStoreStatus status();

    /** 获取或创建设置值。 */
    Optional<String> getSetting(String key);

    /** 更新设置值。 */
    void setSetting(String key, String value);

    /** 获取所有 embedding profiles。 */
    List<EmbeddingProfile> listProfiles();

    /** 按 ID 获取 profile。 */
    Optional<EmbeddingProfile> getProfile(String profileId);

    /** 保存或更新 profile。 */
    void saveProfile(EmbeddingProfile profile);

    /** 关闭连接。 */
    void close();

    /**
     * chunk_embeddings 表的原始行数据。
     */
    record ChunkEmbeddingRow(
            String chunkId,
            String profileId,
            int dimensions,
            byte[] vectorBlob,
            String chunkContentHash,
            String embeddedAt
    ) {}
}
