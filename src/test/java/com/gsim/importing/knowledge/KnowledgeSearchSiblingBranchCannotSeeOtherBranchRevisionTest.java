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

@DisplayName("knowledge search — 兄弟分支隔离: 张三 lunch 分叉例子")
class KnowledgeSearchSiblingBranchCannotSeeOtherBranchRevisionTest {

    @TempDir Path tempDir;
    private SQLiteKnowledgeStore store;

    @BeforeEach
    void setUp() throws Exception {
        Path dbPath = tempDir.resolve("test.db");
        store = new SQLiteKnowledgeStore(dbPath.toString());
        store.initialize();

        // 主线: branch1 → branch2 → branch3
        var k1 = store.upsert(new KnowledgeDocumentInput(
                "entity:张三", "张三是一个人。", "default",
                "branch_output", "", null,
                "root-1", "branch.b0001", "", "entity:张三", "created"));

        var k2 = store.upsert(new KnowledgeDocumentInput(
                "entity:张三", "张三是个男的。", "default",
                "branch_output", "", null,
                "root-1", "branch.b0002", k1.docId(), "entity:张三", "appended"));

        // 主线 branch3: 变性手术
        store.upsert(new KnowledgeDocumentInput(
                "entity:张三", "张三做了变性手术。", "default",
                "branch_output", "", null,
                "root-1", "branch.b0003", k2.docId(), "entity:张三", "state_changed"));

        // 兄弟分支 lunch: 从 b0002 分叉
        store.upsert(new KnowledgeDocumentInput(
                "entity:张三", "张三去吃了顿午饭。", "default",
                "branch_output", "", null,
                "root-1", "branch.b0002-lunch", k2.docId(), "entity:张三", "note"));
    }

    @Test
    @DisplayName("在 lunch 分支搜 变性手术 — 不得返回 branch3 的 k3")
    void lunchBranchCannotSeeBranch3Revision() {
        // lunch 分支可见路径: b0001 → b0002 → b0002-lunch
        List<String> lunchVisibleBranches = List.of(
                "branch.b0001", "branch.b0002", "branch.b0002-lunch");

        var results = store.searchKeyword("变性手术", "default", 10);
        var filtered = store.filterByVisibleBranches(results, lunchVisibleBranches);
        assertTrue(filtered.isEmpty(),
                "branch2-lunch should NOT see k3 (branch.b0003) content about 变性手术");
    }

    @Test
    @DisplayName("在 lunch 分支搜 午饭 → 返回 lunch 可见链: 人+男+午饭，不含变性手术")
    void lunchBranchSearchLunchReturnsLunchChain() {
        List<String> lunchVisibleBranches = List.of(
                "branch.b0001", "branch.b0002", "branch.b0002-lunch");

        var results = store.searchKeyword("午饭", "default", 10);
        var filtered = store.filterByVisibleBranches(results, lunchVisibleBranches);
        assertFalse(filtered.isEmpty(), "should find 午饭 in lunch branch");

        var hitDoc = store.getDocument(filtered.get(0).docId());
        assertTrue(hitDoc.isPresent());
        assertEquals("entity:张三", hitDoc.get().targetKey());
        assertEquals("branch.b0002-lunch", hitDoc.get().branchId());

        // 获取 lunch 分支可见链
        var chain = store.findByTargetKey("entity:张三", lunchVisibleBranches);
        assertEquals(3, chain.size(), "lunch chain: k1 + k2 + k4 = 3 items");

        StringBuilder combined = new StringBuilder();
        for (var doc : chain) combined.append(doc.content()).append("\n");

        assertTrue(combined.toString().contains("张三是一个人。"), "should include k1");
        assertTrue(combined.toString().contains("张三是个男的。"), "should include k2");
        assertTrue(combined.toString().contains("张三去吃了顿午饭。"), "should include k4");
        assertFalse(combined.toString().contains("变性手术"),
                "should NOT include k3 from branch3");
    }

    @Test
    @DisplayName("在主线 branch3 搜 变性手术 → 不包含 lunch 分支的 午饭 内容")
    void mainLineBranch3DoesNotSeeLunchContent() {
        List<String> mainLineVisibleBranches = List.of(
                "branch.b0001", "branch.b0002", "branch.b0003");

        var chain = store.findByTargetKey("entity:张三", mainLineVisibleBranches);
        assertEquals(3, chain.size(), "main line chain: k1 + k2 + k3 = 3 items");

        StringBuilder combined = new StringBuilder();
        for (var doc : chain) combined.append(doc.content()).append("\n");

        assertTrue(combined.toString().contains("张三是一个人。"));
        assertTrue(combined.toString().contains("张三是个男的。"));
        assertTrue(combined.toString().contains("张三做了变性手术。"));
        assertFalse(combined.toString().contains("午饭"),
                "main line should NOT include k4 from lunch branch");
    }
}
