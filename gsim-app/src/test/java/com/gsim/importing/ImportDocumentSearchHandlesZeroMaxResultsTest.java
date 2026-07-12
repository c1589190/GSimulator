package com.gsim.importing;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("ImportDocumentSearch — 处理 maxResults <= 0")
class ImportDocumentSearchHandlesZeroMaxResultsTest {

    @TempDir Path tempDir;
    private ImportDocumentService service;

    @BeforeEach
    void setUp() throws Exception {
        Path importDir = tempDir.resolve("import");
        Files.createDirectories(importDir);
        // 文档中多次出现关键词
        Files.writeString(importDir.resolve("multi.txt"),
                "apple banana apple cherry apple date apple elderberry");
        service = new ImportDocumentService(importDir);
    }

    @Test
    @DisplayName("maxResults=0 被 clamp 到默认值 1，至少返回一个结果")
    void zeroMaxResultsClampedToOne() throws Exception {
        List<ImportDocumentService.ImportDocumentSearchMatch> results =
                service.searchDocuments("apple", null, null, 0, 300, false);

        // maxResults=0 被 clamp 到 1
        assertTrue(results.size() >= 1, "should return at least 1 result when maxResults=0");
        assertTrue(results.size() <= 1, "should return at most 1 result when maxResults was 0");
    }

    @Test
    @DisplayName("maxResults 为负数被 clamp 到 1")
    void negativeMaxResultsClampedToOne() throws Exception {
        List<ImportDocumentService.ImportDocumentSearchMatch> results =
                service.searchDocuments("apple", null, null, -5, 300, false);

        assertTrue(results.size() >= 1, "should return at least 1 result when maxResults is negative");
        assertTrue(results.size() <= 1);
    }

    @Test
    @DisplayName("contextChars 为负数被 clamp 到 0")
    void negativeContextCharsClampedToZero() throws Exception {
        // 不应抛异常
        List<ImportDocumentService.ImportDocumentSearchMatch> results =
                service.searchDocuments("apple", null, null, 10, -100, false);

        assertFalse(results.isEmpty(), "should still find matches even with negative contextChars");
        // preview 可能很短甚至为空
        for (var m : results) {
            assertNotNull(m.preview(), "preview should not be null");
        }
    }

    @Test
    @DisplayName("正常 maxResults=3 限制结果数")
    void normalMaxResultsWorks() throws Exception {
        List<ImportDocumentService.ImportDocumentSearchMatch> results =
                service.searchDocuments("apple", null, null, 3, 300, false);

        assertTrue(results.size() <= 3, "should not exceed maxResults=3");
    }
}
