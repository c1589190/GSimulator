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

@DisplayName("branch scoped facts with knowledge_upsert — 分支事实写入 embDB 验收")
class KnowledgeUpsertUsedForBranchScopedFactsTest {

    @TempDir Path tempDir;
    private SQLiteKnowledgeStore store;

    @BeforeEach
    void setUp() throws Exception {
        Path dbPath = tempDir.resolve("test.db");
        store = new SQLiteKnowledgeStore(dbPath.toString());
        store.initialize();
    }

    @Test
    @DisplayName("knowledge_upsert 写入带 branchId 的分支知识")
    void upsertStoresBranchId() {
        var result = store.upsert(new KnowledgeDocumentInput(
                "设定:龙门地势", "龙门位于移动城邦，周边为荒漠。",
                "default", "branch_output", "", null,
                "root-1", "branch.b0002", "", "setting:龙门地势", "created"));

        assertTrue(result.success());
        var doc = store.getDocument(result.docId()).orElseThrow();
        assertEquals("root-1", doc.rootId());
        assertEquals("branch.b0002", doc.branchId());
        assertEquals("setting:龙门地势", doc.targetKey());
    }

    @Test
    @DisplayName("knowledge_upsert 可按 targetKey 查找当前分支可见修改链")
    void targetKeyEnablesChainLookup() {
        var r1 = store.upsert(new KnowledgeDocumentInput(
                "entity:王五", "王五是龙门防卫局局长。", "default",
                "branch_output", "", null,
                "root-1", "branch.b0001", "", "entity:王五", "created"));

        store.upsert(new KnowledgeDocumentInput(
                "entity:王五", "王五在战斗中负伤。", "default",
                "branch_output", "", null,
                "root-1", "branch.b0002", r1.docId(), "entity:王五", "state_changed"));

        List<String> visible = List.of("branch.b0001", "branch.b0002");
        var chain = store.findByTargetKey("entity:王五", visible);
        assertEquals(2, chain.size(), "should find both original and revision");
    }

    @Test
    @DisplayName("玩家状态变化通过 knowledge_upsert targetKey 归类")
    void playerStateChangeGroupedByTargetKey() {
        store.upsert(new KnowledgeDocumentInput(
                "entity:player:张三", "张三力量=10 敏捷=8", "default",
                "branch_output", "", null,
                "root-1", "branch.b0001", "", "entity:player:张三", "created"));

        store.upsert(new KnowledgeDocumentInput(
                "entity:player:张三", "张三力量+2 =12", "default",
                "branch_output", "", null,
                "root-1", "branch.b0002", "", "entity:player:张三", "state_changed"));

        List<String> visible = List.of("branch.b0001", "branch.b0002");
        var chain = store.findByTargetKey("entity:player:张三", visible);
        assertEquals(2, chain.size());
    }

    @Test
    @DisplayName("knowledge_upsert 支持 root_world_update 对应的长期档案写入")
    void rootFileUpdateStillSupported() {
        // root_world_update 在 RootToolFactory 中是通过 DataManager 直接写文件，
        // 不经过 embDB — 这是预期行为。此测试确认 embDB 侧逻辑正确。
        var result = store.upsert(new KnowledgeDocumentInput(
                "长期设定:世界背景", "泰拉世界，移动城邦时代。", "default",
                "manual_note", "", null,
                "root-1", "", "", "setting:世界背景", "created"));

        assertTrue(result.success());
        // 无 branchId 表示这是 root 级全局知识
        var doc = store.getDocument(result.docId()).orElseThrow();
        assertTrue(doc.branchId().isEmpty(), "root-level knowledge should have empty branchId");
    }
}
