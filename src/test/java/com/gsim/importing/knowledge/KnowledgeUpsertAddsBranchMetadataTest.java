package com.gsim.importing.knowledge;

import com.gsim.knowledge.KnowledgeDocumentInput;
import com.gsim.knowledge.KnowledgeUpsertResult;
import com.gsim.knowledge.store.KnowledgeSchemaMigrator;
import com.gsim.knowledge.store.SQLiteKnowledgeStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.DriverManager;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("knowledge_upsert — 写入 branch 元数据")
class KnowledgeUpsertAddsBranchMetadataTest {

    @TempDir Path tempDir;
    private SQLiteKnowledgeStore store;

    @BeforeEach
    void setUp() throws Exception {
        Path dbPath = tempDir.resolve("test.db");
        store = new SQLiteKnowledgeStore(dbPath.toString());
        store.initialize();
    }

    @Test
    @DisplayName("upsert 带 branchId/rootId 的文档，读取后字段正确")
    void upsertWithBranchMetadata() {
        var input = new KnowledgeDocumentInput(
                "测试知识", "这是知识内容", "default",
                "agent_note", "", null,
                "root-test", "branch.b0001", "", "entity:张三", "created");

        KnowledgeUpsertResult result = store.upsert(input);
        assertTrue(result.success(), "upsert should succeed: " + result.error());

        var doc = store.getDocument(result.docId());
        assertTrue(doc.isPresent());
        assertEquals("root-test", doc.get().rootId());
        assertEquals("branch.b0001", doc.get().branchId());
        assertEquals("entity:张三", doc.get().targetKey());
        assertEquals("created", doc.get().changeType());
    }

    @Test
    @DisplayName("无 branchId 时默认空字符串（兼容旧数据）")
    void upsertWithoutBranchIdDefaultsToEmpty() {
        var input = new KnowledgeDocumentInput(
                "旧知识", "内容", "default",
                "agent_note", "", null);

        KnowledgeUpsertResult result = store.upsert(input);
        assertTrue(result.success());

        var doc = store.getDocument(result.docId());
        assertTrue(doc.isPresent());
        assertEquals("", doc.get().branchId());
        assertEquals("", doc.get().rootId());
    }

    @Test
    @DisplayName("revisionOf 不为空时正确存储")
    void upsertWithRevision() {
        // 先插入父知识
        var parentInput = new KnowledgeDocumentInput(
                "父知识", "原始内容", "default",
                "agent_note", "", null,
                "root-1", "branch.b0000-start", "", "entity:张三", "created");
        var parentResult = store.upsert(parentInput);
        assertTrue(parentResult.success());

        // 再插入修改
        var revisionInput = new KnowledgeDocumentInput(
                "修改知识", "修改后的内容", "default",
                "agent_note", "", null,
                "root-1", "branch.b0001", parentResult.docId(), "entity:张三", "corrected");
        var revisionResult = store.upsert(revisionInput);
        assertTrue(revisionResult.success());
        assertNotEquals(parentResult.docId(), revisionResult.docId(),
                "revision should be a new document, not overwrite parent");

        var revisionDoc = store.getDocument(revisionResult.docId());
        assertTrue(revisionDoc.isPresent());
        assertEquals(parentResult.docId(), revisionDoc.get().revisionOf());
        assertEquals("corrected", revisionDoc.get().changeType());

        // 父知识仍然存在且未被修改
        var parentDoc = store.getDocument(parentResult.docId());
        assertTrue(parentDoc.isPresent());
        assertEquals("原始内容", parentDoc.get().content());
    }
}
