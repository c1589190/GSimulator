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

@DisplayName("knowledge search — 不返回兄弟分支数据")
class KnowledgeSearchDoesNotReturnSiblingBranchDataTest {

    @TempDir Path tempDir;
    private SQLiteKnowledgeStore store;

    @BeforeEach
    void setUp() throws Exception {
        Path dbPath = tempDir.resolve("test.db");
        store = new SQLiteKnowledgeStore(dbPath.toString());
        store.initialize();

        // 主线: branch1 → branch2 → branch3
        store.upsert(new KnowledgeDocumentInput(
                "主线知识", "主线branch2的知识", "default",
                "agent_note", "", null,
                "root-1", "branch.b0002", "", "", "created"));

        // 兄弟分支: branch1 → branch2-alt
        store.upsert(new KnowledgeDocumentInput(
                "兄弟知识", "兄弟分支的知识", "default",
                "agent_note", "", null,
                "root-1", "branch.b0002-alt", "", "", "created"));
    }

    @Test
    @DisplayName("在主线路径上搜索，不返回兄弟分支的知识")
    void mainLineCannotSeeSiblingBranch() {
        var allResults = store.searchKeyword("知识", "default", 10);
        assertEquals(2, allResults.size());

        // 当前在 branch.b0002（主线），可见路径: b0000-start → b0001 → b0002
        List<String> visibleBranches = List.of(
                "branch.b0000-start", "branch.b0001", "branch.b0002");
        var filtered = store.filterByVisibleBranches(allResults, visibleBranches);
        assertEquals(1, filtered.size(), "should only see main line knowledge");

        var doc = store.getDocument(filtered.get(0).docId());
        assertTrue(doc.isPresent());
        assertEquals("branch.b0002", doc.get().branchId());
    }

    @Test
    @DisplayName("在兄弟分支路径上搜索，不返回主线后续分支的知识")
    void siblingBranchCannotSeeMainLineDescendant() {
        var allResults = store.searchKeyword("知识", "default", 10);

        // 当前在 branch.b0002-alt（兄弟），可见路径: b0000-start → b0001 → b0002-alt
        List<String> visibleBranches = List.of(
                "branch.b0000-start", "branch.b0001", "branch.b0002-alt");
        var filtered = store.filterByVisibleBranches(allResults, visibleBranches);
        assertEquals(1, filtered.size(), "should only see sibling branch knowledge");

        var doc = store.getDocument(filtered.get(0).docId());
        assertTrue(doc.isPresent());
        assertEquals("branch.b0002-alt", doc.get().branchId());
    }
}
