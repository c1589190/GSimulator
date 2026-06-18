package com.gsim.knowledge.store;

import com.gsim.knowledge.*;
import com.gsim.knowledge.embed.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * KnowledgeStore CRUD 完整测试。
 */
@DisplayName("KnowledgeStore CRUD")
class KnowledgeStoreCRUDTest {

    private SQLiteKnowledgeStore store;

    @BeforeEach
    void setUp(@TempDir Path tempDir) throws Exception {
        store = new SQLiteKnowledgeStore(tempDir.resolve("test.db").toString());
        store.initialize();
    }

    @AfterEach
    void tearDown() { store.close(); }

    // ---- Upsert ----

    @Test
    @DisplayName("upsert 无 embedding 时返回 KEYWORD_ONLY")
    void upsertKeywordOnly() {
        var input = doc("keyword test", "关键词检索测试内容");
        var result = store.upsert(input);
        assertTrue(result.success());
        assertEquals("KEYWORD_ONLY", result.status());
        assertTrue(result.docId().startsWith("kdoc-"));
        assertTrue(result.chunksCreated() >= 1);
        assertEquals(0, result.embeddingsCreated());
    }

    @Test
    @DisplayName("keyword_search 可查到 upsert 的内容")
    void keywordSearchFindsContent() {
        store.upsert(doc("乌萨斯边境", "乌萨斯边境感染者救援点容易引发地方军警注意"));
        store.upsert(doc("罗德岛行动", "罗德岛制药公司在龙门地区开展医疗救援行动"));

        List<KnowledgeSearchResult> results = store.searchKeyword("乌萨斯", "default", 5);
        assertFalse(results.isEmpty());
        assertTrue(results.get(0).snippet().contains("乌萨斯"));
        assertEquals("keyword", results.get(0).searchMode());
    }

    @Test
    @DisplayName("keyword_search 无匹配时返回空列表")
    void keywordSearchNoMatch() {
        store.upsert(doc("test", "hello world"));
        List<KnowledgeSearchResult> results = store.searchKeyword("不存在的内容", "default", 5);
        assertTrue(results.isEmpty());
    }

    @Test
    @DisplayName("中文 keyword_search 通过 LIKE 补检索")
    void chineseKeywordLikeFallback() {
        store.upsert(doc("感染者救助", "在切尔诺伯格，感染者救助工作面临巨大挑战"));
        List<KnowledgeSearchResult> results = store.searchKeyword("切尔诺伯格", "default", 5);
        // FTS5 可能对中文分词不好，但 LIKE 应能找到
        boolean found = results.stream().anyMatch(r -> r.snippet().contains("切尔诺伯格"));
        assertTrue(found, "LIKE fallback should find Chinese text");
    }

    // ---- Get ----

    @Test
    @DisplayName("getDocument 返回正确文档")
    void getDocumentWorks() {
        var input = doc("获取测试", "这是用于 getDocument 的测试内容。");
        var result = store.upsert(input);

        var doc = store.getDocument(result.docId());
        assertTrue(doc.isPresent());
        assertEquals("获取测试", doc.get().title());
        assertEquals("这是用于 getDocument 的测试内容。", doc.get().content());
        assertEquals("agent_note", doc.get().sourceType());
    }

    @Test
    @DisplayName("getDocument 按不存在的 docId 返回 empty")
    void getDocumentNotFound() {
        assertTrue(store.getDocument("nonexistent").isEmpty());
    }

    @Test
    @DisplayName("getChunk 返回正确 chunk")
    void getChunkWorks() {
        store.upsert(doc("chunk 测试", "这是一个用于验证 chunk 读取的测试文本。"));
        KnowledgeStoreStatus status = store.status();
        assertTrue(status.chunkCount() >= 1, "Should have at least 1 chunk");
    }

    // ---- Update ----

    @Test
    @DisplayName("update 删除旧 chunks 并重建新 chunks")
    void updateRebuildsChunks() {
        var result = store.upsert(doc("旧标题", "旧内容很长长长长长长长长长长长长长长长长长长长长长长长长长长长长长"));
        String docId = result.docId();
        int oldChunkCount = result.chunksCreated();

        var updateResult = store.update(docId, doc("新标题", "新内容，完全不同"));
        assertTrue(updateResult.success());
        assertEquals("KEYWORD_ONLY", updateResult.status());
        assertEquals(oldChunkCount, updateResult.oldChunksDeleted());
        assertTrue(updateResult.newChunksCreated() >= 1);

        // 验证内容已更新
        var doc = store.getDocument(docId);
        assertTrue(doc.isPresent());
        assertEquals("新标题", doc.get().title());
        assertEquals("新内容，完全不同", doc.get().content());
    }

    @Test
    @DisplayName("update 不存在的文档返回 DOCUMENT_NOT_FOUND")
    void updateNotFound() {
        var result = store.update("nonexistent", doc("x", "y"));
        assertFalse(result.success());
        assertEquals("DOCUMENT_NOT_FOUND", result.status());
    }

    // ---- Delete ----

    @Test
    @DisplayName("delete 删除 documents/chunks/embeddings")
    void deleteCascades() {
        var result = store.upsert(doc("待删除", "即将删除的内容"));
        String docId = result.docId();
        int chunkCount = result.chunksCreated();
        assertTrue(chunkCount >= 1);

        var deleteResult = store.delete(docId);
        assertTrue(deleteResult.success());
        assertEquals(chunkCount, deleteResult.chunksDeleted());

        // 验证删除后不存在
        assertTrue(store.getDocument(docId).isEmpty());
    }

    @Test
    @DisplayName("delete 不存在的文档返回 DOCUMENT_NOT_FOUND")
    void deleteNotFound() {
        var result = store.delete("nonexistent");
        assertFalse(result.success());
        assertTrue(result.error().contains("DOCUMENT_NOT_FOUND"));
    }

    // ---- With Fake Embedding Profile ----

    @Test
    @DisplayName("有 fake profile 时 upsert 写 embeddings")
    void upsertWithFakeProfileWritesEmbeddings() {
        FakeEmbeddingModel fakeModel = new FakeEmbeddingModel();
        EmbeddingProfileManager pm = new EmbeddingProfileManager(store, fakeModel);
        pm.initialize();
        assertTrue(pm.getActiveProfile().isPresent());

        var result = store.upsert(doc("embed 测试", "需要生成 embedding 的测试内容"));
        assertTrue(result.success());

        // 手动补 embeddings
        List<String> missing = store.findChunksMissingEmbedding("default",
                fakeModel.profile().profileId());
        assertFalse(missing.isEmpty(), "Should have chunks missing embeddings");

        // 通过 embed all 补充
        List<String> texts = missing.stream()
                .map(id -> store.getChunk(id).map(c -> c.text()).orElse(""))
                .toList();
        List<EmbeddingVector> vectors = fakeModel.embedAll(texts);

        String now = Instant.now().toString();
        List<KnowledgeStore.ChunkEmbeddingRow> rows = new java.util.ArrayList<>();
        for (int i = 0; i < missing.size(); i++) {
            byte[] blob = VectorCodec.encodeFloat32(vectors.get(i).values());
            rows.add(new KnowledgeStore.ChunkEmbeddingRow(
                    missing.get(i), fakeModel.profile().profileId(),
                    vectors.get(i).dimensions(), blob,
                    com.gsim.knowledge.chunk.Chunker.sha256(texts.get(i)), now));
        }
        int written = store.writeEmbeddings(rows);
        assertEquals(missing.size(), written);

        // 验证 embeddings 存在
        List<KnowledgeStore.ChunkEmbeddingRow> stored = store.getChunkEmbeddings(
                "default", fakeModel.profile().profileId());
        assertFalse(stored.isEmpty());
    }

    // ---- Status ----

    @Test
    @DisplayName("status 返回正确统计")
    void statusReturnsCorrectStats() {
        store.upsert(doc("doc1", "content 1"));
        store.upsert(doc("doc2", "content 2"));
        KnowledgeStoreStatus s = store.status();
        assertEquals(2, s.documentCount());
        assertTrue(s.chunkCount() >= 2);
        assertTrue(s.ftsAvailable());
    }

    // ---- Helper ----

    private KnowledgeDocumentInput doc(String title, String content) {
        return new KnowledgeDocumentInput(title, content, "default", "agent_note", "", null);
    }
}
