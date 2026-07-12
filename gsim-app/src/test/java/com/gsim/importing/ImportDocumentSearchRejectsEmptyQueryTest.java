package com.gsim.importing;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("ImportDocumentSearch — 拒绝空 query")
class ImportDocumentSearchRejectsEmptyQueryTest {

    @TempDir Path tempDir;
    private ImportDocumentService service;

    @BeforeEach
    void setUp() throws Exception {
        Path importDir = tempDir.resolve("import");
        Files.createDirectories(importDir);
        Files.writeString(importDir.resolve("doc.txt"), "some content here");
        service = new ImportDocumentService(importDir);
    }

    @Test
    @DisplayName("空字符串 query 抛出 IMPORT_QUERY_EMPTY")
    void emptyQueryThrowsImportQueryEmpty() {
        var ex = assertThrows(ImportDocumentService.ImportDocumentException.class,
                () -> service.searchDocuments("", null, null, 10, 300, false));
        assertEquals("IMPORT_QUERY_EMPTY", ex.errorCode());
    }

    @Test
    @DisplayName("blank query（仅空格）抛出 IMPORT_QUERY_EMPTY")
    void blankQueryThrowsImportQueryEmpty() {
        var ex = assertThrows(ImportDocumentService.ImportDocumentException.class,
                () -> service.searchDocuments("   ", null, null, 10, 300, false));
        assertEquals("IMPORT_QUERY_EMPTY", ex.errorCode());
    }

    @Test
    @DisplayName("null query 抛出 IMPORT_QUERY_EMPTY")
    void nullQueryThrowsImportQueryEmpty() {
        var ex = assertThrows(ImportDocumentService.ImportDocumentException.class,
                () -> service.searchDocuments(null, null, null, 10, 300, false));
        assertEquals("IMPORT_QUERY_EMPTY", ex.errorCode());
    }

    @Test
    @DisplayName("正常 query 不抛异常")
    void normalQueryDoesNotThrow() throws Exception {
        var results = service.searchDocuments("content", null, null, 10, 300, false);
        assertEquals(1, results.size());
        assertEquals("doc.txt", results.get(0).displayName());
    }
}
