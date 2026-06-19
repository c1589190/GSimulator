package com.gsim.importing;

import com.gsim.knowledge.KnowledgeDocumentInput;
import com.gsim.knowledge.store.KnowledgeSchemaMigrator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("ImportDocumentRead — 不写入 KnowledgeStore")
class ImportDocumentReadDoesNotWriteKnowledgeStoreTest {

    @TempDir Path tempDir;
    private Path importDir;
    private ImportDocumentService service;
    private Path dbPath;

    @BeforeEach
    void setUp() throws Exception {
        importDir = tempDir.resolve("import");
        Files.createDirectories(importDir);
        Files.writeString(importDir.resolve("test.txt"), "测试内容");

        service = new ImportDocumentService(importDir);

        dbPath = tempDir.resolve("test.db");
        try (Connection conn = DriverManager.getConnection("jdbc:sqlite:" + dbPath)) {
            new KnowledgeSchemaMigrator().migrate(conn);
        }
    }

    @Test
    @DisplayName("import_document_read 不会把内容写入 KnowledgeStore")
    void readDoesNotWriteToKnowledgeStore() throws Exception {
        var result = service.readDocument("test.txt", 0, 8000, false);
        assertTrue(result.content().contains("测试内容"));

        // 验证 KnowledgeStore 没有新文档
        try (Connection conn = DriverManager.getConnection("jdbc:sqlite:" + dbPath);
             var stmt = conn.createStatement();
             var rs = stmt.executeQuery("SELECT COUNT(*) FROM documents")) {
            assertEquals(0, rs.getInt(1), "KnowledgeStore should be empty after import_document_read");
        }
    }

    @Test
    @DisplayName("import_document_search 不会把内容写入 KnowledgeStore")
    void searchDoesNotWriteToKnowledgeStore() throws Exception {
        var matches = service.searchDocuments("测试", null, null, 10, 300, false);
        assertFalse(matches.isEmpty());

        try (Connection conn = DriverManager.getConnection("jdbc:sqlite:" + dbPath);
             var stmt = conn.createStatement();
             var rs = stmt.executeQuery("SELECT COUNT(*) FROM documents")) {
            assertEquals(0, rs.getInt(1), "KnowledgeStore should be empty after import_document_search");
        }
    }
}
