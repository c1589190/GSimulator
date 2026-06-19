package com.gsim.importing.knowledge;

import com.gsim.knowledge.KnowledgeDocumentInput;
import com.gsim.knowledge.store.SQLiteKnowledgeStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("knowledge revision — 修改作为独立知识单元存储")
class KnowledgeRevisionStoredAsIndependentUnitTest {

    @TempDir Path tempDir;
    private SQLiteKnowledgeStore store;

    @BeforeEach
    void setUp() throws Exception {
        Path dbPath = tempDir.resolve("test.db");
        store = new SQLiteKnowledgeStore(dbPath.toString());
        store.initialize();
    }

    @Test
    @DisplayName("修改项不覆盖父知识，存储为独立文档")
    void revisionStoredAsIndependentUnit() {
        // k1: 原始
        var k1Result = store.upsert(new KnowledgeDocumentInput(
                "entity:张三", "张三是一个人。", "default",
                "branch_output", "", null,
                "root-1", "branch.b0001", "", "entity:张三", "created"));
        assertTrue(k1Result.success());
        String k1Id = k1Result.docId();

        // k2: 修改（不应覆盖 k1）
        var k2Result = store.upsert(new KnowledgeDocumentInput(
                "entity:张三", "张三是个男的。", "default",
                "branch_output", "", null,
                "root-1", "branch.b0002", k1Id, "entity:张三", "appended"));
        assertTrue(k2Result.success());
        String k2Id = k2Result.docId();
        assertNotEquals(k1Id, k2Id, "revision must be a new document");

        // k1 内容不受影响
        var k1Doc = store.getDocument(k1Id);
        assertTrue(k1Doc.isPresent());
        assertEquals("张三是一个人。", k1Doc.get().content());
        assertEquals("", k1Doc.get().revisionOf());

        // k2 是独立文档
        var k2Doc = store.getDocument(k2Id);
        assertTrue(k2Doc.isPresent());
        assertEquals("张三是个男的。", k2Doc.get().content());
        assertEquals(k1Id, k2Doc.get().revisionOf());
    }
}
