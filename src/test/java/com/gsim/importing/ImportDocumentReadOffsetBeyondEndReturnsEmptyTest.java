package com.gsim.importing;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("ImportDocumentRead — offset 超尾返回空而非重置为 0")
class ImportDocumentReadOffsetBeyondEndReturnsEmptyTest {

    @TempDir Path tempDir;
    private ImportDocumentService service;

    @BeforeEach
    void setUp() throws Exception {
        Path importDir = tempDir.resolve("import");
        Files.createDirectories(importDir);
        Files.writeString(importDir.resolve("short.txt"), "hello");
        service = new ImportDocumentService(importDir);
    }

    @Test
    @DisplayName("offset 超尾返回空 content，不重置为 0")
    void offsetBeyondEndReturnsEmpty() throws Exception {
        var result = service.readDocument("short.txt", 100, 100, false);

        // short.txt 只有 5 字符，offset=100 >= 5
        assertTrue(result.content().isEmpty(), "content should be empty when offset beyond end");
        assertFalse(result.truncated(), "truncated should be false");
        assertEquals("none", result.nextOffset(), "nextOffset should be 'none'");
        assertEquals(5, result.originalLength(), "originalLength should still be reported");
        assertEquals(100, result.offset(), "offset should be preserved as-is");
    }

    @Test
    @DisplayName("offset 小于 0 被重置为 0")
    void negativeOffsetClampedToZero() throws Exception {
        var result = service.readDocument("short.txt", -5, 100, false);

        assertEquals(0, result.offset(), "negative offset should be clamped to 0");
        assertEquals("hello", result.content(), "should read from beginning");
    }

    @Test
    @DisplayName("offset 恰等于 originalLength 返回空")
    void offsetExactlyAtEndReturnsEmpty() throws Exception {
        var result = service.readDocument("short.txt", 5, 100, false);

        assertTrue(result.content().isEmpty(), "content should be empty when offset == length");
        assertFalse(result.truncated());
        assertEquals("none", result.nextOffset());
    }
}
