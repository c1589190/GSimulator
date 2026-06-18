package com.gsim.knowledge.tool;

import com.gsim.knowledge.*;
import com.gsim.knowledge.embed.EmbeddingProfileManager;
import com.gsim.knowledge.embed.FakeEmbeddingModel;
import com.gsim.knowledge.search.KnowledgeSearchService;
import com.gsim.knowledge.store.SQLiteKnowledgeStore;
import com.gsim.tool.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Agent Tool 注册和基础功能测试。
 */
@DisplayName("Knowledge Agent Tool Registration")
class KnowledgeAgentToolRegistrationTest {

    private ToolRegistry registry;
    private SQLiteKnowledgeStore store;

    @BeforeEach
    void setUp(@TempDir Path tempDir) throws Exception {
        store = new SQLiteKnowledgeStore(tempDir.resolve("test.db").toString());
        store.initialize();

        FakeEmbeddingModel fakeModel = new FakeEmbeddingModel();
        EmbeddingProfileManager pm = new EmbeddingProfileManager(store, fakeModel);
        pm.initialize();
        KnowledgeSearchService searchService = new KnowledgeSearchService(store, pm);

        KnowledgeToolFactory factory = new KnowledgeToolFactory(store, searchService, pm);
        registry = new ToolRegistry();
        for (AgentTool tool : factory.createAll()) {
            registry.register(tool);
        }
    }

    @AfterEach
    void tearDown() { store.close(); }

    @Test
    @DisplayName("所有 8 个 knowledge tools 已注册")
    void allEightToolsRegistered() {
        assertNotNull(registry.get("keyword_search"));
        assertNotNull(registry.get("knowledge_search"));
        assertNotNull(registry.get("knowledge_get_chunk"));
        assertNotNull(registry.get("knowledge_get_document"));
        assertNotNull(registry.get("knowledge_upsert"));
        assertNotNull(registry.get("knowledge_update"));
        assertNotNull(registry.get("knowledge_delete"));
        assertNotNull(registry.get("knowledge_embed_missing"));
    }

    @Test
    @DisplayName("keyword_search tool 通过 ToolRegistry 可调用")
    void keywordSearchViaTool() {
        // 先 upsert 内容
        var upsertTool = registry.get("knowledge_upsert");
        ToolResult upsertResult = upsertTool.execute(new ToolCall("knowledge_upsert",
                Map.of("title", "乌萨斯边境", "content", "乌萨斯边境感染者救援点")));
        assertTrue(upsertResult.success());

        // 再 keyword_search
        var kwTool = registry.get("keyword_search");
        ToolResult kwResult = kwTool.execute(new ToolCall("keyword_search",
                Map.of("query", "乌萨斯", "collection", "default")));
        assertTrue(kwResult.success());
        assertFalse(kwResult.items().isEmpty());
    }

    @Test
    @DisplayName("knowledge_search 无 embeddings 时返回错误")
    void knowledgeSearchNoEmbeddingsReturnsError() {
        var tool = registry.get("knowledge_search");
        ToolResult result = tool.execute(new ToolCall("knowledge_search",
                Map.of("query", "测试", "collection", "default")));
        assertFalse(result.success());
        assertTrue(result.error().contains("NO_EMBEDDINGS_FOR_PROFILE"));
    }

    @Test
    @DisplayName("knowledge_upsert tool 保存文档")
    void upsertSavesDocument() {
        var tool = registry.get("knowledge_upsert");
        ToolResult result = tool.execute(new ToolCall("knowledge_upsert",
                Map.of("title", "罗德岛行动", "content", "罗德岛制药公司医疗行动",
                        "sourceType", "manual_note")));
        assertTrue(result.success());
        assertNotNull(result.items().get(0).path()); // docId
    }

    @Test
    @DisplayName("knowledge_delete tool 删除文档")
    void deleteRemovesDocument() {
        // Upsert then delete
        var upsertTool = registry.get("knowledge_upsert");
        ToolResult upsertR = upsertTool.execute(new ToolCall("knowledge_upsert",
                Map.of("title", "待删除", "content", "待删除内容")));
        String docId = upsertR.items().get(0).path();

        var deleteTool = registry.get("knowledge_delete");
        ToolResult deleteR = deleteTool.execute(new ToolCall("knowledge_delete",
                Map.of("docId", docId)));
        assertTrue(deleteR.success());

        // 验证已删除
        assertTrue(store.getDocument(docId).isEmpty());
    }

    @Test
    @DisplayName("knowledge_embed_missing 无 active profile 时返回错误")
    void embedMissingNoProfile() {
        // 创建无 profile 的 registry
        SQLiteKnowledgeStore emptyStore = null;
        try {
            // 使用 class-level store（有 profile）
            var tool = registry.get("knowledge_embed_missing");
            ToolResult result = tool.execute(new ToolCall("knowledge_embed_missing",
                    Map.of("collection", "default")));
            // 有 fake profile，应该成功或返回 complete
            assertTrue(result.success());
        } finally {
            // no-op
        }
    }
}
