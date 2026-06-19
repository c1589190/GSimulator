package com.gsim.importing.knowledge;

import com.gsim.knowledge.KnowledgeDocumentInput;
import com.gsim.knowledge.store.SQLiteKnowledgeStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("knowledge revision — 不覆盖父知识单元")
class KnowledgeRevisionDoesNotOverwriteParentTest {

    @TempDir Path tempDir;
    private SQLiteKnowledgeStore store;

    @BeforeEach
    void setUp() throws Exception {
        Path dbPath = tempDir.resolve("test.db");
        store = new SQLiteKnowledgeStore(dbPath.toString());
        store.initialize();
    }

    @Test
    @DisplayName("多次追加修改后，父知识和中间修改项内容均不变")
    void parentContentPreservedAfterRevisions() {
        // k1
        var r1 = store.upsert(new KnowledgeDocumentInput(
                "entity:张三", "张三是一个人。", "default",
                "branch_output", "", null,
                "root-1", "branch.b0001", "", "entity:张三", "created"));
        String k1Id = r1.docId();

        // k2
        var r2 = store.upsert(new KnowledgeDocumentInput(
                "entity:张三", "张三是个男的。", "default",
                "branch_output", "", null,
                "root-1", "branch.b0002", k1Id, "entity:张三", "appended"));
        String k2Id = r2.docId();

        // k3
        var r3 = store.upsert(new KnowledgeDocumentInput(
                "entity:张三", "张三做了变性手术。", "default",
                "branch_output", "", null,
                "root-1", "branch.b0003", k2Id, "entity:张三", "state_changed"));
        String k3Id = r3.docId();

        // 验证所有文档独立存在，内容不变
        assertEquals("张三是一个人。", store.getDocument(k1Id).get().content());
        assertEquals("张三是个男的。", store.getDocument(k2Id).get().content());
        assertEquals("张三做了变性手术。", store.getDocument(k3Id).get().content());

        // 计数: 3 个独立文档
        var docs = store.findByTargetKey("entity:张三", null);
        assertEquals(3, docs.size());
    }
}
