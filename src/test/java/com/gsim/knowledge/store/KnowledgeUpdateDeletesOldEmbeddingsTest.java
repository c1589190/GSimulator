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
 * 验证 knowledge_update 删除顺序正确：旧 chunk embeddings 必须被删除，数据库不残留。
 */
@DisplayName("KnowledgeUpdate Deletes Old Embeddings")
class KnowledgeUpdateDeletesOldEmbeddingsTest {

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
    @DisplayName("update doc → 旧 chunk embeddings 被删除")
    void updateDeletesOldChunkEmbeddings() {
        // 1. upsert 文档
        var input = doc("原始标题", "这是原始文档内容，用于测试更新操作。");
        var upsertResult = store.upsert(input);
        assertTrue(upsertResult.success());
        String docId = upsertResult.docId();

        // 2. 为原始 chunks 生成 embeddings
        List<String> missingIds = store.findChunksMissingEmbeddingForDoc(
                docId, fakeModel.profile().profileId());
        assertFalse(missingIds.isEmpty(), "新 upsert 的 doc chunks 应有缺失 embeddings");

        // 生成并写入 embeddings
        writeEmbeddingsForChunks(missingIds);

        // 验证 embeddings 已写入
        var embeddingsAfterWrite = store.getChunkEmbeddings("default", fakeModel.profile().profileId());
        assertFalse(embeddingsAfterWrite.isEmpty(), "写入后应有 embeddings");

        // 记录旧 chunk_ids
        List<String> oldChunkIds = store.getChunkEmbeddings("default", fakeModel.profile().profileId())
                .stream().map(KnowledgeStore.ChunkEmbeddingRow::chunkId).toList();

        // 3. update 同一个文档
        var updateInput = doc("更新标题", "这是完全不同的更新后文档内容，用于测试旧 embeddings 删除。");
        var updateResult = store.update(docId, updateInput);
        assertTrue(updateResult.success());
        assertTrue(updateResult.oldChunksDeleted() > 0, "应有旧 chunks 被删除");
        assertTrue(updateResult.oldEmbeddingsDeleted() > 0, "应有旧 embeddings 被删除");

        // 4. 验证旧 chunk_ids 对应的 embeddings 已不存在
        for (String oldChunkId : oldChunkIds) {
            List<KnowledgeStore.ChunkEmbeddingRow> rows = store.getChunkEmbeddings(
                    "default", fakeModel.profile().profileId());
            boolean foundOld = rows.stream()
                    .anyMatch(r -> r.chunkId().equals(oldChunkId));
            assertFalse(foundOld, "旧 chunk_id " + oldChunkId + " 的 embedding 应已被删除");
        }

        // 5. 验证新 chunks 存在且没有旧 embeddings
        List<String> newMissingIds = store.findChunksMissingEmbeddingForDoc(
                docId, fakeModel.profile().profileId());
        assertFalse(newMissingIds.isEmpty(), "新 chunks 应有缺失 embeddings（未自动生成）");
    }

    @Test
    @DisplayName("update doc → 新 chunk embeddings 可以被正确写入")
    void updateThenEmbedNewChunks() {
        // 1. upsert + embed
        var input = doc("原始", "原始内容。");
        var result = store.upsert(input);
        String docId = result.docId();
        writeEmbeddingsForChunks(store.findChunksMissingEmbeddingForDoc(
                docId, fakeModel.profile().profileId()));

        // 2. update
        var updateInput = doc("更新", "全新的更新内容，完全不同的文本。");
        store.update(docId, updateInput);

        // 3. 为新 chunks 生成 embeddings
        List<String> newMissing = store.findChunksMissingEmbeddingForDoc(
                docId, fakeModel.profile().profileId());
        assertFalse(newMissing.isEmpty());
        writeEmbeddingsForChunks(newMissing);

        // 4. 验证新 embeddings 存在且数量与新 chunks 一致
        var embeddingsAfter = store.getChunkEmbeddings("default", fakeModel.profile().profileId());
        assertFalse(embeddingsAfter.isEmpty());
        // 所有 embeddings 的 chunk_id 应该都是当前 chunks 表中的
        for (var row : embeddingsAfter) {
            var chunk = store.getChunk(row.chunkId());
            assertTrue(chunk.isPresent(), "embedding 的 chunk_id " + row.chunkId() + " 应存在于 chunks 表");
        }
    }

    private void writeEmbeddingsForChunks(List<String> chunkIds) {
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
