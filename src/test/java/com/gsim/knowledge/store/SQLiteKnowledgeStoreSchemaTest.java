package com.gsim.knowledge.store;

import com.gsim.knowledge.*;
import com.gsim.knowledge.embed.FakeEmbeddingModel;
import com.gsim.knowledge.chunk.Chunker;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.*;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * SQLite schema 自动创建测试。
 */
@DisplayName("SQLiteKnowledgeStore Schema")
class SQLiteKnowledgeStoreSchemaTest {

    private SQLiteKnowledgeStore store;
    private Path dbPath;

    @BeforeEach
    void setUp(@TempDir Path tempDir) throws Exception {
        dbPath = tempDir.resolve("test.db");
        store = new SQLiteKnowledgeStore(dbPath.toString());
        store.initialize();
    }

    @AfterEach
    void tearDown() {
        store.close();
    }

    @Test
    @DisplayName("所有 6 张表在 initialize 后存在")
    void allTablesCreated() throws Exception {
        try (Connection conn = DriverManager.getConnection("jdbc:sqlite:" + dbPath);
             Statement stmt = conn.createStatement()) {
            ResultSet rs = stmt.executeQuery(
                    "SELECT name FROM sqlite_master WHERE type='table' ORDER BY name");
            List<String> tables = new java.util.ArrayList<>();
            while (rs.next()) tables.add(rs.getString("name"));
            assertTrue(tables.contains("documents"), "documents table missing");
            assertTrue(tables.contains("chunks"), "chunks table missing");
            assertTrue(tables.contains("chunk_fts"), "chunk_fts table missing");
            assertTrue(tables.contains("embedding_profiles"), "embedding_profiles table missing");
            assertTrue(tables.contains("chunk_embeddings"), "chunk_embeddings table missing");
            assertTrue(tables.contains("knowledge_settings"), "knowledge_settings table missing");
        }
    }

    @Test
    @DisplayName("initialize 是幂等的 — 重复调用不抛异常")
    void initializeIsIdempotent() throws Exception {
        store.initialize();
        store.initialize();
        // 不应抛异常
        assertTrue(store.getSetting(KnowledgeSettings.KEY_KNOWLEDGE_STORE_VERSION).isPresent());
    }

    @Test
    @DisplayName("knowledge_settings 默认值被设置")
    void defaultSettingsSet() {
        assertTrue(store.getSetting(KnowledgeSettings.KEY_KNOWLEDGE_STORE_VERSION).isPresent());
        assertTrue(store.getSetting(KnowledgeSettings.KEY_DEFAULT_COLLECTION).isPresent());
        assertEquals("default", store.getSetting(KnowledgeSettings.KEY_DEFAULT_COLLECTION).get());
    }

    @Test
    @DisplayName("upsert 写入 documents/chunks/fts")
    void upsertWritesAllTables() {
        KnowledgeDocumentInput input = new KnowledgeDocumentInput(
                "测试标题", "这是一段测试内容，用于验证 upsert 写入。",
                "test-col", "agent_note", "", null);
        KnowledgeUpsertResult result = store.upsert(input);

        assertTrue(result.success());
        assertEquals("KEYWORD_ONLY", result.status());
        assertEquals(1, result.chunksCreated());

        // 验证 document
        var doc = store.getDocument(result.docId());
        assertTrue(doc.isPresent());
        assertEquals("测试标题", doc.get().title());
        assertEquals("test-col", doc.get().collection());
    }
}
