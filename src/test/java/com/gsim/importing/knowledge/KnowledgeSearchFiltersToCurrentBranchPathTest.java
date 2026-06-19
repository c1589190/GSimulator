package com.gsim.importing.knowledge;

import com.gsim.knowledge.KnowledgeDocumentInput;
import com.gsim.knowledge.KnowledgeSearchResult;
import com.gsim.knowledge.store.SQLiteKnowledgeStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("knowledge search — 按当前 branch 祖先路径过滤")
class KnowledgeSearchFiltersToCurrentBranchPathTest {

    @TempDir Path tempDir;
    private SQLiteKnowledgeStore store;

    @BeforeEach
    void setUp() throws Exception {
        Path dbPath = tempDir.resolve("test.db");
        store = new SQLiteKnowledgeStore(dbPath.toString());
        store.initialize();
    }

    @Test
    @DisplayName("filterByVisibleBranches 只保留祖先路径内的文档")
    void filterByVisibleBranches() {
        // branch1 = root 分支
        store.upsert(new KnowledgeDocumentInput(
                "知识1", "根分支的知识", "default",
                "agent_note", "", null,
                "root-1", "branch.b0000-start", "", "", "created"));

        // branch2 = 当前分支的祖先
        store.upsert(new KnowledgeDocumentInput(
                "知识2", "当前分支祖先的知识", "default",
                "agent_note", "", null,
                "root-1", "branch.b0001", "", "", "created"));

        // branch3 = 兄弟分支，不在当前路径内
        store.upsert(new KnowledgeDocumentInput(
                "知识3", "兄弟分支的知识", "default",
                "agent_note", "", null,
                "root-1", "branch.b0002-alt", "", "", "created"));

        // 搜索所有
        var allResults = store.searchKeyword("知识", "default", 10);
        assertEquals(3, allResults.size(), "should find all 3 docs");

        // 过滤为仅 b0000-start → b0001 路径可见
        List<String> visibleBranches = List.of("branch.b0000-start", "branch.b0001");
        var filtered = store.filterByVisibleBranches(allResults, visibleBranches);
        assertEquals(2, filtered.size(), "should only see 2 docs in ancestor path");
        for (var r : filtered) {
            var doc = store.getDocument(r.docId());
            assertTrue(doc.isPresent());
            assertNotEquals("branch.b0002-alt", doc.get().branchId(),
                    "兄弟分支的内容不应可见");
        }
    }

    @Test
    @DisplayName("filterByVisibleBranches 保留 branchId 为空的旧数据")
    void emptyBranchIdPassesFilter() {
        // 旧数据没有 branchId
        store.upsert(new KnowledgeDocumentInput(
                "旧知识", "旧内容", "default",
                "agent_note", "", null));

        var allResults = store.searchKeyword("旧知识", "default", 10);
        assertEquals(1, allResults.size());

        // 即使严格过滤，branchId 为空的旧数据也应通过
        var filtered = store.filterByVisibleBranches(allResults,
                List.of("branch.b0000-start"));
        assertEquals(1, filtered.size(), "empty branchId should pass filter (backward compat)");
    }
}
