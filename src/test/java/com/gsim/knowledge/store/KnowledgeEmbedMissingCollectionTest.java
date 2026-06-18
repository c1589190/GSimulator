package com.gsim.knowledge.store;

import com.gsim.knowledge.*;
import com.gsim.knowledge.embed.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 验证 knowledge_embed_missing 功能：只补全有缺失 embedding 的 chunks。
 */
@DisplayName("KnowledgeEmbedMissing Collection Test")
class KnowledgeEmbedMissingCollectionTest {

    private SQLiteKnowledgeStore store;
    private FakeEmbeddingModel fakeModel;

    @BeforeEach
    void setUp(@TempDir Path tempDir) throws Exception {
        store = new SQLiteKnowledgeStore(tempDir.resolve("test.db").toString());
        store.initialize();
        fakeModel = new FakeEmbeddingModel();
        store.saveProfile(fakeModel.profile());
    }

    @AfterEach
    void tearDown() { store.close(); }

    @Test
    @DisplayName("collection 中有 docA、docB，只 docA 缺 embedding → embed_missing 补全 docA")
    void embedMissingFillsOnlyMissing() {
        String profileId = fakeModel.profile().profileId();

        // 1. upsert docA + docB
        var resultA = store.upsert(doc("文档A", "文档A的内容。"));
        var resultB = store.upsert(doc("文档B", "文档B的内容。"));
        String docAId = resultA.docId();
        String docBId = resultB.docId();

        // 2. 只为 docB 生成 embeddings
        List<String> docBMissing = store.findChunksMissingEmbeddingForDoc(docBId, profileId);
        embedChunks(docBMissing);
        assertTrue(store.findChunksMissingEmbeddingForDoc(docBId, profileId).isEmpty());

        // 3. docA 仍缺
        List<String> docAMissing = store.findChunksMissingEmbeddingForDoc(docAId, profileId);
        assertFalse(docAMissing.isEmpty());

        // 4. collection 级别 → 只找到 docA 缺失
        List<String> allMissing = store.findChunksMissingEmbedding("default", profileId);
        assertFalse(allMissing.isEmpty());
        for (String missingId : allMissing) {
            var chunk = store.getChunk(missingId);
            assertTrue(chunk.isPresent());
            assertNotEquals(docBId, chunk.get().docId(),
                    "docB 已有 embedding，不应出现在缺失列表");
        }

        // 5. embed_missing 补全
        embedChunks(allMissing);

        // 6. 全 collection 不再缺
        assertTrue(store.findChunksMissingEmbedding("default", profileId).isEmpty());
        assertTrue(store.findChunksMissingEmbeddingForDoc(docAId, profileId).isEmpty());
        assertTrue(store.findChunksMissingEmbeddingForDoc(docBId, profileId).isEmpty());
    }

    @Test
    @DisplayName("collection 全有 embedding → findChunksMissingEmbedding 返回空")
    void noMissingReturnsEmpty() {
        String profileId = fakeModel.profile().profileId();

        var result = store.upsert(doc("文档", "内容。"));
        List<String> missing = store.findChunksMissingEmbeddingForDoc(result.docId(), profileId);
        embedChunks(missing);

        List<String> allMissing = store.findChunksMissingEmbedding("default", profileId);
        assertTrue(allMissing.isEmpty(), "所有 chunks 已有 embedding，不应有缺失");
    }

    @Test
    @DisplayName("无 active profile 时 upsert 不生成 embedding")
    void upsertWithoutProfile() {
        // 不注册 profile → upsert 不生成 embedding
        var result = store.upsert(doc("无profile", "测试内容。"));
        assertTrue(result.success());
        assertEquals("KEYWORD_ONLY", result.status());
        assertEquals(0, result.embeddingsCreated());
    }

    private void embedChunks(List<String> chunkIds) {
        if (chunkIds.isEmpty()) return;
        String now = Instant.now().toString();
        List<KnowledgeStore.ChunkEmbeddingRow> rows = new ArrayList<>();
        for (String chunkId : chunkIds) {
            var chunk = store.getChunk(chunkId);
            if (chunk.isPresent()) {
                EmbeddingVector vec = fakeModel.embed(chunk.get().text());
                byte[] blob = VectorCodec.encodeFloat32(vec.values());
                rows.add(new KnowledgeStore.ChunkEmbeddingRow(
                        chunkId, fakeModel.profile().profileId(), vec.dimensions(),
                        blob, chunk.get().contentHash(), now));
            }
        }
        store.writeEmbeddings(rows);
    }

    private static KnowledgeDocumentInput doc(String title, String content) {
        return new KnowledgeDocumentInput(title, content, "default",
                "test", "", null);
    }
}
