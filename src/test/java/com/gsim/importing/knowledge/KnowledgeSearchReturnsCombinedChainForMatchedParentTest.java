package com.gsim.importing.knowledge;

import com.gsim.knowledge.*;
import com.gsim.knowledge.store.SQLiteKnowledgeStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("knowledge search — 命中原始项时返回完整修改链 combinedContent")
class KnowledgeSearchReturnsCombinedChainForMatchedParentTest {

    @TempDir Path tempDir;
    private SQLiteKnowledgeStore store;

    @BeforeEach
    void setUp() throws Exception {
        Path dbPath = tempDir.resolve("test.db");
        store = new SQLiteKnowledgeStore(dbPath.toString());
        store.initialize();

        // 主线: branch1 → branch2 → branch3
        // k1: branch1, 原始
        var r1 = store.upsert(new KnowledgeDocumentInput(
                "entity:张三", "张三是一个人。", "default",
                "branch_output", "", null,
                "root-1", "branch.b0001", "", "entity:张三", "created"));

        // k2: branch2, 修订 k1
        store.upsert(new KnowledgeDocumentInput(
                "entity:张三", "张三是个男的。", "default",
                "branch_output", "", null,
                "root-1", "branch.b0002", r1.docId(), "entity:张三", "appended"));

        // k3: branch3, 修订 k2
        store.upsert(new KnowledgeDocumentInput(
                "entity:张三", "张三做了变性手术。", "default",
                "branch_output", "", null,
                "root-1", "branch.b0003", r1.docId(), "entity:张三", "state_changed"));
    }

    @Test
    @DisplayName("搜 人 命中 k1 → combinedContent 包含 k1+k2+k3 完整链")
    void searchHitOnParentReturnsFullChain() {
        // 当前在 branch3，可见路径 b0001 → b0002 → b0003
        List<String> visibleBranches = List.of("branch.b0001", "branch.b0002", "branch.b0003");

        var results = store.searchKeyword("人", "default", 10);
        var filtered = store.filterByVisibleBranches(results, visibleBranches);
        assertFalse(filtered.isEmpty());

        // 获取 targetKey 并构建完整链
        var hitDoc = store.getDocument(filtered.get(0).docId());
        assertTrue(hitDoc.isPresent());
        assertEquals("entity:张三", hitDoc.get().targetKey());

        var chain = store.findByTargetKey("entity:张三", visibleBranches);
        assertEquals(3, chain.size(), "should have all 3 items in ancestor path");

        StringBuilder combined = new StringBuilder();
        for (var doc : chain) {
            if (!combined.isEmpty()) combined.append("\n");
            combined.append(doc.content());
        }
        assertTrue(combined.toString().contains("张三是一个人。"));
        assertTrue(combined.toString().contains("张三是个男的。"));
        assertTrue(combined.toString().contains("张三做了变性手术。"));
    }
}
