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

@DisplayName("knowledge search — 命中修改项时也返回完整修改链 combinedContent")
class KnowledgeSearchReturnsCombinedChainForMatchedRevisionTest {

    @TempDir Path tempDir;
    private SQLiteKnowledgeStore store;

    @BeforeEach
    void setUp() throws Exception {
        Path dbPath = tempDir.resolve("test.db");
        store = new SQLiteKnowledgeStore(dbPath.toString());
        store.initialize();

        // 主线: branch1 → branch2 → branch3
        var r1 = store.upsert(new KnowledgeDocumentInput(
                "entity:张三", "张三是一个人。", "default",
                "branch_output", "", null,
                "root-1", "branch.b0001", "", "entity:张三", "created"));

        var r2 = store.upsert(new KnowledgeDocumentInput(
                "entity:张三", "张三是个男的。", "default",
                "branch_output", "", null,
                "root-1", "branch.b0002", r1.docId(), "entity:张三", "appended"));

        store.upsert(new KnowledgeDocumentInput(
                "entity:张三", "张三做了变性手术。", "default",
                "branch_output", "", null,
                "root-1", "branch.b0003", r2.docId(), "entity:张三", "state_changed"));
    }

    @Test
    @DisplayName("搜 变性手术 命中 k3 → combinedContent 应包含完整链 人+男+变性手术")
    void searchHitOnRevisionReturnsFullChain() {
        List<String> visibleBranches = List.of("branch.b0001", "branch.b0002", "branch.b0003");

        // 搜索 变性手术 — 只命中 k3
        var results = store.searchKeyword("变性手术", "default", 10);
        var filtered = store.filterByVisibleBranches(results, visibleBranches);
        assertFalse(filtered.isEmpty());

        var hitDoc = store.getDocument(filtered.get(0).docId());
        assertTrue(hitDoc.isPresent());
        assertEquals("entity:张三", hitDoc.get().targetKey());
        assertEquals("state_changed", hitDoc.get().changeType());

        // 通过 targetKey 获取完整链
        var chain = store.findByTargetKey("entity:张三", visibleBranches);
        assertEquals(3, chain.size());

        StringBuilder combined = new StringBuilder();
        for (var doc : chain) {
            if (!combined.isEmpty()) combined.append("\n");
            combined.append(doc.content());
        }
        assertTrue(combined.toString().contains("张三是一个人。"));
        assertTrue(combined.toString().contains("张三是个男的。"));
        assertTrue(combined.toString().contains("张三做了变性手术。"));
    }

    @Test
    @DisplayName("搜 男 命中 k2 → combinedContent 包含完整链")
    void searchHitOnMiddleRevisionReturnsFullChain() {
        List<String> visibleBranches = List.of("branch.b0001", "branch.b0002", "branch.b0003");

        var results = store.searchKeyword("男", "default", 10);
        var filtered = store.filterByVisibleBranches(results, visibleBranches);
        assertFalse(filtered.isEmpty());

        var hitDoc = store.getDocument(filtered.get(0).docId());
        assertTrue(hitDoc.isPresent());
        assertEquals("entity:张三", hitDoc.get().targetKey());

        var chain = store.findByTargetKey("entity:张三", visibleBranches);
        assertEquals(3, chain.size());

        StringBuilder combined = new StringBuilder();
        for (var doc : chain) combined.append(doc.content()).append("\n");
        assertTrue(combined.toString().contains("张三是一个人。"));
        assertTrue(combined.toString().contains("张三是个男的。"));
        assertTrue(combined.toString().contains("张三做了变性手术。"));
    }
}
