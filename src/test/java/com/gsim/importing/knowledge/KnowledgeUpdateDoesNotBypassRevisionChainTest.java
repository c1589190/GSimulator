package com.gsim.importing.knowledge;

import com.gsim.knowledge.KnowledgeDocumentInput;
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

@DisplayName("knowledge_update — 不绕过 revision chain，分支知识不可覆盖")
class KnowledgeUpdateDoesNotBypassRevisionChainTest {

    @TempDir Path tempDir;
    private SQLiteKnowledgeStore store;
    private KnowledgeToolFactory factory;

    @BeforeEach
    void setUp() throws Exception {
        Path dbPath = tempDir.resolve("test.db");
        store = new SQLiteKnowledgeStore(dbPath.toString());
        store.initialize();

        factory = new KnowledgeToolFactory(store, null, null);
    }

    @Test
    @DisplayName("对分支 scoped 文档调用 knowledge_update 返回 KNOWLEDGE_UPDATE_REJECTED_FOR_BRANCH_DOC")
    void updateOnBranchScopedDocIsRejected() {
        // 写入一条分支知识
        var upsertResult = store.upsert(new KnowledgeDocumentInput(
                "entity:张三", "张三是一个人。", "default",
                "branch_output", "", null,
                "root-1", "branch.b0001", "", "entity:张三", "created"));
        String docId = upsertResult.docId();

        // 尝试通过 knowledge_update 覆盖
        var tools = factory.createAll();
        var updateTool = tools.stream()
                .filter(t -> t.name().equals("knowledge_update"))
                .findFirst().orElseThrow();

        ToolCall call = new ToolCall("knowledge_update", java.util.Map.of(
                "docId", docId,
                "title", "entity:张三",
                "content", "张三是个女的。"
        ));

        ToolResult result = updateTool.execute(call);
        assertFalse(result.success(), "update on branch-scoped doc should be rejected");
        assertTrue(result.error().contains("KNOWLEDGE_UPDATE_REJECTED_FOR_BRANCH_DOC"),
                "error should contain KNOWLEDGE_UPDATE_REJECTED_FOR_BRANCH_DOC: " + result.error());
        assertTrue(result.error().contains("revisionOf=" + docId),
                "error should tell agent to use revisionOf=" + docId);
        assertTrue(result.error().contains("targetKey=entity:张三"),
                "error should tell agent to use targetKey=entity:张三");
    }

    @Test
    @DisplayName("对无 branchId 的全局文档调用 knowledge_update 允许执行")
    void updateOnNonBranchDocIsAllowed() {
        // 写入一条无分支的全局知识
        var upsertResult = store.upsert(new KnowledgeDocumentInput(
                "全局设定", "全局内容", "default",
                "agent_note", "", null,
                "", "", "", "", "created"));
        String docId = upsertResult.docId();

        var tools = factory.createAll();
        var updateTool = tools.stream()
                .filter(t -> t.name().equals("knowledge_update"))
                .findFirst().orElseThrow();

        ToolCall call = new ToolCall("knowledge_update", java.util.Map.of(
                "docId", docId,
                "title", "全局设定",
                "content", "更新后的全局内容"
        ));

        ToolResult result = updateTool.execute(call);
        assertTrue(result.success(),
                "update on non-branch doc should be allowed: " + result.error());
    }
}
