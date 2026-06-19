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

@DisplayName("knowledge search — 无 active branch 时不抛 NPE")
class KnowledgeSearchWithoutActiveBranchDoesNotThrowTest {

    @TempDir Path tempDir;
    private SQLiteKnowledgeStore store;

    @BeforeEach
    void setUp() throws Exception {
        Path dbPath = tempDir.resolve("test.db");
        store = new SQLiteKnowledgeStore(dbPath.toString());
        store.initialize();

        // 写入一条无 branchId 的知识（模拟无 root 状态下的全局知识）
        store.upsert(new KnowledgeDocumentInput(
                "通用设定", "这是一条全局知识。", "default",
                "agent_note", "", null,
                "", "", "", "", "created"));
    }

    @Test
    @DisplayName("visibleBranchIds 为空列表时 filterByVisibleBranches 不抛异常")
    void filterWithEmptyVisibleBranchesDoesNotThrow() {
        List<String> emptyBranches = List.of();

        var results = store.searchKeyword("全局知识", "default", 10);
        // 空 branch 列表 = 不过滤
        var filtered = store.filterByVisibleBranches(results, emptyBranches);

        assertFalse(filtered.isEmpty(), "should return results when no branch filter is active");
    }

    @Test
    @DisplayName("visibleBranchIds 为 null 时 filterByVisibleBranches 不抛异常")
    void filterWithNullVisibleBranchesDoesNotThrow() {
        var results = store.searchKeyword("全局知识", "default", 10);
        var filtered = store.filterByVisibleBranches(results, null);

        assertFalse(filtered.isEmpty(), "should return results when visibleBranchIds is null");
    }

    @Test
    @DisplayName("无 active branch 时 searchKeyword 仍可正常工作")
    void searchKeywordWorksWithoutActiveBranch() {
        var results = store.searchKeyword("全局知识", "default", 10);
        assertFalse(results.isEmpty());
    }
}
