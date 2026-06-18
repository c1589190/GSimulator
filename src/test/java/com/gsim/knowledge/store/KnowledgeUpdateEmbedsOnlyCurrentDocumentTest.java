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
 * 验证 knowledge_update 只 embed 当前 doc，不自动 embed collection 中其他文档。
 */
@DisplayName("KnowledgeUpdate Embeds Only Current Document")
class KnowledgeUpdateEmbedsOnlyCurrentDocumentTest {

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
    @DisplayName("update docB 后只 embed docB 新 chunks，不自动 embed 缺 embedding 的 docA")
    void updateDocBOnlyEmbedsDocB() {
        String profileId = fakeModel.profile().profileId();

        // 1. upsert docA（无 embedding 生成）
        var resultA = store.upsert(doc("文档A", "文档A的原始内容，用于测试 update 不自动补其他文档。"));
        assertTrue(resultA.success());
        String docAId = resultA.docId();
        List<String> docAChunkIdsBefore = chunkIdsForDoc(docAId);

        // 验证 docA 缺 embeddings
        List<String> docAMissing = store.findChunksMissingEmbeddingForDoc(docAId, profileId);
        assertEquals(docAChunkIdsBefore.size(), docAMissing.size(), "docA 所有 chunks 应缺 embedding");

        // 2. upsert docB 并为其生成 embeddings
        var resultB = store.upsert(doc("文档B", "文档B的内容。"));
        assertTrue(resultB.success());
        String docBId = resultB.docId();
        embedChunksForDoc(store.findChunksMissingEmbeddingForDoc(docBId, profileId));

        // 验证 docB 已有 embeddings
        assertTrue(store.findChunksMissingEmbeddingForDoc(docBId, profileId).isEmpty());

        // 3. update docB — 只应 embed docB 新 chunks
        var updateInput = doc("文档B-更新", "文档B的更新后内容，完全不同的文本。");
        var updateResult = store.update(docBId, updateInput);
        assertTrue(updateResult.success());

        // 只为 docB 新 chunks 生成 embeddings
        List<String> docBNewMissing = store.findChunksMissingEmbeddingForDoc(docBId, profileId);
        assertFalse(docBNewMissing.isEmpty(), "update 后 docB 新 chunks 应缺 embedding");
        embedChunksForDoc(docBNewMissing);

        // 4. 验证 docB 新 chunks 有 embeddings
        assertTrue(store.findChunksMissingEmbeddingForDoc(docBId, profileId).isEmpty());

        // 5. 验证 docA 仍然缺 embeddings（未被 update docB 触发自动补全）
        List<String> docAStillMissing = store.findChunksMissingEmbeddingForDoc(docAId, profileId);
        assertFalse(docAStillMissing.isEmpty(), "docA 不应被自动 embed（update docB 不应触发全库扫描）");

        // 6. 验证 collection 级查询能找到 docA 缺失
        List<String> allMissing = store.findChunksMissingEmbedding("default", profileId);
        boolean allFromDocA = allMissing.stream().allMatch(id -> {
            var chunk = store.getChunk(id);
            return chunk.isPresent() && chunk.get().docId().equals(docAId);
        });
        assertTrue(allFromDocA, "collection 级缺失应全部来自 docA");
    }

    @Test
    @DisplayName("更新 docB 内容，只 embed docB → docA 不受影响，embed_missing 才补 docA")
    void updateThenEmbedMissingFillsDocAOnly() {
        String profileId = fakeModel.profile().profileId();

        // setup: docA 缺 embedding, docB 已有 embedding
        var resultA = store.upsert(doc("文档A", "文档A的内容。"));
        String docAId = resultA.docId();

        var resultB = store.upsert(doc("文档B", "文档B的内容。"));
        String docBId = resultB.docId();
        embedChunksForDoc(store.findChunksMissingEmbeddingForDoc(docBId, profileId));

        // update docB
        store.update(docBId, doc("文档B-v2", "文档B的第二版内容。"));
        embedChunksForDoc(store.findChunksMissingEmbeddingForDoc(docBId, profileId));

        // docA 仍缺
        assertFalse(store.findChunksMissingEmbeddingForDoc(docAId, profileId).isEmpty());

        // embed_missing 补全
        List<String> allMissing = store.findChunksMissingEmbedding("default", profileId);
        embedChunksForDoc(allMissing);

        // docA 不再缺
        assertTrue(store.findChunksMissingEmbeddingForDoc(docAId, profileId).isEmpty());
        assertTrue(store.findChunksMissingEmbedding("default", profileId).isEmpty());
    }

    private void embedChunksForDoc(List<String> chunkIds) {
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

    private List<String> chunkIdsForDoc(String docId) {
        // 通过 findChunksMissingEmbeddingForDoc 来获取 chunk ids
        // 在没有 profile 时无法用这个方法。改用 searchKeyword 间接验证。
        // 这里我们用 store 的其他方法来获取，如果不行就用 reflection。
        // 实际上最简单的方式是用 findChunksMissingEmbeddingForDoc 获取
        return store.findChunksMissingEmbeddingForDoc(docId, fakeModel.profile().profileId());
    }

    private static KnowledgeDocumentInput doc(String title, String content) {
        return new KnowledgeDocumentInput(title, content, "default",
                "test", "", null);
    }
}
