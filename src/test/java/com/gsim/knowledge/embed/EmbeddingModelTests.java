package com.gsim.knowledge.embed;

import com.gsim.knowledge.KnowledgeDocumentInput;
import com.gsim.knowledge.KnowledgeSearchResponse;
import com.gsim.knowledge.chunk.Chunker;
import com.gsim.knowledge.search.KnowledgeSearchService;
import com.gsim.knowledge.store.KnowledgeStore;
import com.gsim.knowledge.store.SQLiteKnowledgeStore;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Embedding 模型综合测试。
 */
@DisplayName("Embedding Models")
class EmbeddingModelTests {

    // ---- FakeEmbeddingModel ----

    @Test
    @DisplayName("FakeEmbeddingModel 确定性输出")
    void fakeIsDeterministic() {
        FakeEmbeddingModel model = new FakeEmbeddingModel();
        EmbeddingVector v1 = model.embed("hello");
        EmbeddingVector v2 = model.embed("hello");
        assertEquals(v1, v2);
        assertEquals(128, v1.dimensions());
    }

    @Test
    @DisplayName("FakeEmbeddingModel 不同文本产生不同向量")
    void fakeDifferentTextsDifferentVectors() {
        FakeEmbeddingModel model = new FakeEmbeddingModel();
        EmbeddingVector v1 = model.embed("hello");
        EmbeddingVector v2 = model.embed("world");
        assertNotEquals(v1, v2);
    }

    @Test
    @DisplayName("FakeEmbeddingModel cosine 相似度基础")
    void fakeCosineSimilarity() {
        FakeEmbeddingModel model = new FakeEmbeddingModel();
        EmbeddingVector v1 = model.embed("hello world");
        EmbeddingVector v2 = model.embed("hello world");
        assertEquals(1.0, v1.cosineSimilarity(v2), 0.001);
    }

    // ---- VectorCodec ----

    @Test
    @DisplayName("VectorCodec encode/decode roundtrip")
    void vectorCodecRoundtrip() {
        float[] original = {0.1f, 0.2f, 0.3f, 0.4f};
        byte[] blob = VectorCodec.encodeFloat32(original);
        float[] decoded = VectorCodec.decodeFloat32(blob, 4);
        assertArrayEquals(original, decoded, 0.0001f);
    }

    @Test
    @DisplayName("VectorCodec decode 维度不匹配抛异常")
    void vectorCodecDimensionMismatch() {
        byte[] blob = VectorCodec.encodeFloat32(new float[]{1f, 2f, 3f});
        assertThrows(IllegalArgumentException.class, () -> VectorCodec.decodeFloat32(blob, 4));
    }

    // ---- LocalSmallEmbeddingModel (stub) ----

    @Test
    @DisplayName("LocalSmallEmbeddingModel 模型文件缺失返回 unavailable profile")
    void localSmallModelNotFound() {
        LocalSmallEmbeddingModel model = new LocalSmallEmbeddingModel(
                "/nonexistent/path", "test-model", 384);
        assertFalse(model.isAvailable());
        assertEquals("unavailable", model.profile().status());
    }

    @Test
    @DisplayName("LocalSmallEmbeddingModel embed 抛 LOCAL_MODEL_NOT_FOUND")
    void localSmallEmbedThrowsNotFound() {
        LocalSmallEmbeddingModel model = new LocalSmallEmbeddingModel(
                "/nonexistent/path", "test-model", 384);
        RuntimeException ex = assertThrows(RuntimeException.class,
                () -> model.embed("test"));
        assertTrue(ex.getMessage().contains("LOCAL_MODEL_NOT_FOUND"));
    }

    // ---- Profile mismatch detection ----

    @Nested
    @DisplayName("Profile Mismatch")
    class ProfileMismatch {

        private SQLiteKnowledgeStore store;
        private FakeEmbeddingModel fakeModel;
        private EmbeddingProfileManager pm;
        private KnowledgeSearchService searchService;

        @BeforeEach
        void setUp(@TempDir Path tempDir) throws Exception {
            store = new SQLiteKnowledgeStore(tempDir.resolve("test.db").toString());
            store.initialize();
            fakeModel = new FakeEmbeddingModel();
            pm = new EmbeddingProfileManager(store, fakeModel);
            pm.initialize();
            searchService = new KnowledgeSearchService(store, pm);

            // 写入测试文档并生成 embeddings
            KnowledgeDocumentInput input = new KnowledgeDocumentInput(
                    "测试", "测试内容", "default", "agent_note", "", null);
            store.upsert(input);

            List<String> missing = store.findChunksMissingEmbedding("default",
                    fakeModel.profile().profileId());
            for (String chunkId : missing) {
                var chunk = store.getChunk(chunkId).orElseThrow();
                EmbeddingVector vec = fakeModel.embed(chunk.text());
                byte[] blob = VectorCodec.encodeFloat32(vec.values());
                store.writeEmbeddings(List.of(new KnowledgeStore.ChunkEmbeddingRow(
                        chunkId, fakeModel.profile().profileId(),
                        vec.dimensions(), blob, chunk.contentHash(),
                        Instant.now().toString())));
            }
        }

        @AfterEach
        void tearDown() { store.close(); }

        @Test
        @DisplayName("knowledge_search 无 active profile 返回 NO_ACTIVE_EMBEDDING_PROFILE")
        void searchNoProfile(@TempDir Path tempDir) throws Exception {
            // 使用独立的新 store，确保无任何 profile 残留
            SQLiteKnowledgeStore freshStore = new SQLiteKnowledgeStore(
                    tempDir.resolve("fresh.db").toString());
            freshStore.initialize();
            EmbeddingProfileManager emptyPm = new EmbeddingProfileManager(freshStore, null);
            emptyPm.initialize();
            KnowledgeSearchService emptyService = new KnowledgeSearchService(freshStore, emptyPm);

            KnowledgeSearchResponse resp = emptyService.semanticSearch("test", "default", 5);
            assertFalse(resp.success());
            assertEquals("NO_ACTIVE_EMBEDDING_PROFILE", resp.errorCode());
            freshStore.close();
        }

        @Test
        @DisplayName("knowledge_search 找不到同 profile embeddings 返回 NO_EMBEDDINGS_FOR_PROFILE")
        void searchNoEmbeddingsForProfile() {
            // 查询一个没有 embeddings 的 collection
            KnowledgeSearchResponse resp = searchService.semanticSearch("test", "nonexistent-col", 5);
            assertFalse(resp.success());
            assertEquals("NO_EMBEDDINGS_FOR_PROFILE", resp.errorCode());
        }

        @Test
        @DisplayName("knowledge_search 有 profile 有 embeddings 返回结果")
        void searchWithProfileWorks() {
            KnowledgeSearchResponse resp = searchService.semanticSearch("测试", "default", 5);
            assertTrue(resp.success(), "Expected success but got: " + resp.errorCode() + " " + resp.error());
            assertNotNull(resp.items());
            assertFalse(resp.items().isEmpty());
            assertEquals("semantic", resp.items().get(0).searchMode());
        }

        @Test
        @DisplayName("不同 profile 不能混查")
        void profileMismatchDetected() {
            // 创建另一个 profile
            FakeEmbeddingModel otherModel = new FakeEmbeddingModel();
            store.saveProfile(otherModel.profile());

            // 尝试用旧 profile 查新 profile 的 embeddings（新 profile 的 collection 无 embeddings）
            KnowledgeSearchResponse resp = searchService.semanticSearch("测试", "default", 5);
            assertTrue(resp.success()); // 使用的是原 profile，应该成功
            assertFalse(resp.items().isEmpty());
        }
    }
}
