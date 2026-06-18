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
 * 验证 knowledge_upsert 只 embed 当前 doc，不自动 embed collection 中其他文档。
 */
@DisplayName("KnowledgeUpsert Embeds Only Current Document")
class KnowledgeUpsertEmbedsOnlyCurrentDocumentTest {

    private SQLiteKnowledgeStore store;
    private FakeEmbeddingModel fakeModel;

    @BeforeEach
    void setUp(@TempDir Path tempDir) throws Exception {
        store = new SQLiteKnowledgeStore(tempDir.resolve("test.db").toString());
        store.initialize();
        fakeModel = new FakeEmbeddingModel();

        // 注册 fake profile
        store.saveProfile(fakeModel.profile());
    }

    @AfterEach
    void tearDown() { store.close(); }

    @Test
    @DisplayName("upsert docB 时只 embed docB，不自动 embed 缺 embedding 的 docA")
    void upsertDocBOnlyEmbedsDocB() {
        String profileId = fakeModel.profile().profileId();

        // 1. upsert docA (无 embedding 生成)
        var docAInput = doc("文档A", "这是文档A的内容，用于测试 embedding 范围。");
        var resultA = store.upsert(docAInput);
        assertTrue(resultA.success());
        String docAId = resultA.docId();

        // 验证 docA 缺 embeddings
        List<String> docAMissing = store.findChunksMissingEmbeddingForDoc(docAId, profileId);
        assertFalse(docAMissing.isEmpty(), "docA 应有缺失 embeddings（未自动生成）");

        // 2. upsert docB — 只用 findChunksMissingEmbeddingForDoc 生成 docB 的 embeddings
        var docBInput = doc("文档B", "这是文档B的内容。");
        var resultB = store.upsert(docBInput);
        assertTrue(resultB.success());
        String docBId = resultB.docId();

        // 只为 docB 生成 embeddings
        List<String> docBMissing = store.findChunksMissingEmbeddingForDoc(docBId, profileId);
        assertFalse(docBMissing.isEmpty());
        embedChunksForDoc(docBMissing);

        // 3. 验证 docB 有 embeddings
        List<String> docBAfterEmbed = store.findChunksMissingEmbeddingForDoc(docBId, profileId);
        assertTrue(docBAfterEmbed.isEmpty(), "docB 的 embeddings 已生成，不应有缺失");

        // 4. 验证 docA 仍然缺 embeddings（未被自动 embed）
        List<String> docAStillMissing = store.findChunksMissingEmbeddingForDoc(docAId, profileId);
        assertFalse(docAStillMissing.isEmpty(), "docA 不应被自动 embed，仍应有缺失 embeddings");

        // 5. 验证用 collection 级别查询可以同时找到两者的缺失
        // （docA 应该有缺失，docB 不应该）
        // 但 findChunksMissingEmbedding(collection, profileId) 应该能找到 docA 的缺失
        List<String> allMissing = store.findChunksMissingEmbedding("default", profileId);
        assertFalse(allMissing.isEmpty(), "collection 级别查询应找到 docA 的缺失");
        // docB 的 chunk_ids 不应在缺失列表中
        for (String missingId : allMissing) {
            var chunk = store.getChunk(missingId);
            assertTrue(chunk.isPresent());
            assertNotEquals(docBId, chunk.get().docId(),
                    "docB 的 chunk 不应出现在缺失列表中");
        }
    }

    @Test
    @DisplayName("knowledge_embed_missing 才补 docA 缺失的 embeddings")
    void embedMissingFillsDocA() {
        String profileId = fakeModel.profile().profileId();

        // 1. upsert docA（无 embedding）
        var resultA = store.upsert(doc("文档A", "文档A的内容。"));
        String docAId = resultA.docId();

        // 2. upsert docB 并只为 docB embed
        var resultB = store.upsert(doc("文档B", "文档B的内容。"));
        String docBId = resultB.docId();
        embedChunksForDoc(store.findChunksMissingEmbeddingForDoc(docBId, profileId));

        // 3. docA 仍缺
        assertFalse(store.findChunksMissingEmbeddingForDoc(docAId, profileId).isEmpty());

        // 4. knowledge_embed_missing 补全 collection 缺失（包含 docA）
        List<String> allMissing = store.findChunksMissingEmbedding("default", profileId);
        embedChunksForDoc(allMissing);

        // 5. docA 不再缺
        assertTrue(store.findChunksMissingEmbeddingForDoc(docAId, profileId).isEmpty());

        // 6. collection 级别也干净
        assertTrue(store.findChunksMissingEmbedding("default", profileId).isEmpty());
    }

    private void embedChunksForDoc(List<String> chunkIds) {
        if (chunkIds.isEmpty()) return;
        String now = Instant.now().toString();
        List<KnowledgeStore.ChunkEmbeddingRow> rows = new java.util.ArrayList<>();
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
        int written = store.writeEmbeddings(rows);
        assertTrue(written > 0, "应写入至少一个 embedding");
    }

    private static KnowledgeDocumentInput doc(String title, String content) {
        return new KnowledgeDocumentInput(title, content, "default",
                "test", "", null);
    }
}
