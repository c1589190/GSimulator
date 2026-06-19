package com.gsim.importing.knowledge;

import com.gsim.knowledge.store.SQLiteKnowledgeStore;
import com.gsim.knowledge.tool.KnowledgeToolFactory;
import com.gsim.tool.ToolCall;
import com.gsim.tool.ToolResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("knowledge_upsert — 无 active root/branch 时返回清晰错误")
class KnowledgeUpsertWithoutActiveBranchReturnsClearErrorTest {

    @TempDir Path tempDir;
    private SQLiteKnowledgeStore store;
    private KnowledgeToolFactory factory;

    @BeforeEach
    void setUp() throws Exception {
        Path dbPath = tempDir.resolve("test.db");
        store = new SQLiteKnowledgeStore(dbPath.toString());
        store.initialize();

        factory = new KnowledgeToolFactory(store, null, null);
        // 设置 context suppliers 但返回空 — 模拟已配置但无 active root/branch
        factory.setContextSuppliers(
                () -> "",
                () -> "",
                () -> List.of());
    }

    @Test
    @DisplayName("context suppliers 已配置但 root/branch 均为空时 upsert 返回 NO_ACTIVE_ROOT_OR_BRANCH")
    void upsertWithoutActiveBranchReturnsClearError() {
        var tools = factory.createAll();
        var upsertTool = tools.stream()
                .filter(t -> t.name().equals("knowledge_upsert"))
                .findFirst().orElseThrow();

        ToolCall call = new ToolCall("knowledge_upsert", java.util.Map.of(
                "title", "测试知识",
                "content", "测试内容"
        ));

        ToolResult result = upsertTool.execute(call);
        assertFalse(result.success(), "should fail when no active root/branch");
        assertTrue(result.error().contains("NO_ACTIVE_ROOT_OR_BRANCH"),
                "error should contain NO_ACTIVE_ROOT_OR_BRANCH: " + result.error());
    }

    @Test
    @DisplayName("有 rootId 但无 branchId 时允许写入（bootstrap 场景）")
    void upsertWithRootIdButNoBranchIdAllowed() throws Exception {
        // 直接通过 store 写入带 rootId 的知识 — 兼容 bootstrap 场景
        var input = new com.gsim.knowledge.KnowledgeDocumentInput(
                "测试", "内容", "default", "agent_note", "", null,
                "root-1", "", "", "", "created");
        var result = store.upsert(input);
        assertTrue(result.success(), "should allow write with rootId even without branchId");
    }

    @Test
    @DisplayName("有 branchId 但无 rootId 时允许写入")
    void upsertWithBranchIdButNoRootIdAllowed() throws Exception {
        var input = new com.gsim.knowledge.KnowledgeDocumentInput(
                "测试", "内容", "default", "agent_note", "", null,
                "", "branch.b0001", "", "", "created");
        var result = store.upsert(input);
        assertTrue(result.success(), "should allow write with branchId even without rootId");
    }
}
