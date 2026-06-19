package com.gsim.importing.knowledge;

import com.gsim.knowledge.KnowledgeDocumentInput;
import com.gsim.knowledge.store.SQLiteKnowledgeStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("branch extra files 不写入实体增量 — 使用 embDB 代替")
class BranchExtraFilesAreNotWrittenForEntityUpdatesTest {

    @TempDir Path tempDir;
    private SQLiteKnowledgeStore store;

    @BeforeEach
    void setUp() throws Exception {
        Path dbPath = tempDir.resolve("test.db");
        store = new SQLiteKnowledgeStore(dbPath.toString());
        store.initialize();
    }

    @Test
    @DisplayName("实体状态变化通过 knowledge_upsert 写入 embDB 带 branchId")
    void entityStateChangeWrittenToEmbDbWithBranchId() {
        var result = store.upsert(new KnowledgeDocumentInput(
                "entity:player:张三 力量+3", "张三的力量值从 10 提升到 13。",
                "default", "branch_output", "", null,
                "root-1", "branch.b0002", "", "entity:player:张三", "state_changed"));

        assertTrue(result.success(), "entity state change should be saved to embDB");
        assertNotNull(result.docId());

        var doc = store.getDocument(result.docId());
        assertTrue(doc.isPresent());
        assertEquals("root-1", doc.get().rootId());
        assertEquals("branch.b0002", doc.get().branchId());
        assertEquals("entity:player:张三", doc.get().targetKey());
        assertEquals("state_changed", doc.get().changeType());
    }

    @Test
    @DisplayName("势力关系变化通过 knowledge_upsert 写入 embDB")
    void factionRelationChangeWrittenToEmbDb() {
        var result = store.upsert(new KnowledgeDocumentInput(
                "relation:罗德岛-龙门", "罗德岛与龙门建立同盟关系。",
                "default", "branch_output", "", null,
                "root-1", "branch.b0002", "", "relation:罗德岛-龙门", "state_changed"));

        assertTrue(result.success());
        var doc = store.getDocument(result.docId());
        assertTrue(doc.isPresent());
        assertEquals("relation:罗德岛-龙门", doc.get().targetKey());
    }

    @Test
    @DisplayName("规则临时增量通过 knowledge_upsert 写入 embDB")
    void ruleChangeWrittenToEmbDb() {
        var result = store.upsert(new KnowledgeDocumentInput(
                "rule:天气系统", "本回合起启用沙尘暴天气规则。",
                "default", "branch_output", "", null,
                "root-1", "branch.b0003", "", "rule:天气系统", "appended"));

        assertTrue(result.success());
        var doc = store.getDocument(result.docId());
        assertTrue(doc.isPresent());
        assertEquals("rule:天气系统", doc.get().targetKey());
        assertEquals("appended", doc.get().changeType());
    }

    @Test
    @DisplayName("对已有实体的修改不覆盖父知识，建立 revisionOf 链")
    void modificationUsesRevisionChain() {
        // 原始知识
        var k1 = store.upsert(new KnowledgeDocumentInput(
                "entity:李四", "李四位于龙门。", "default",
                "branch_output", "", null,
                "root-1", "branch.b0001", "", "entity:李四", "created"));
        String k1Id = k1.docId();

        // 修改 — 新知识单元，revisionOf=k1
        var k2 = store.upsert(new KnowledgeDocumentInput(
                "entity:李四", "李四离开龙门前往乌萨斯。", "default",
                "branch_output", "", null,
                "root-1", "branch.b0002", k1Id, "entity:李四", "state_changed"));

        assertTrue(k2.success());
        assertNotEquals(k1Id, k2.docId(), "revision must be independent");

        // 父知识内容不变
        var k1Doc = store.getDocument(k1Id);
        assertTrue(k1Doc.isPresent());
        assertEquals("李四位于龙门。", k1Doc.get().content());
    }
}
