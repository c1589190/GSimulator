package com.gsim.core.importing;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * ImportDocumentService 读取上限配置注入测试 — 断言构造参数流入分页读取。
 */
@DisplayName("ImportDocumentService 上限配置注入")
class ImportDocumentLimitConfigTest {

    @TempDir
    Path tempDir;

    @Test
    @DisplayName("full=true 使用注入的 maxFullReadChars 上限")
    void fullReadUsesInjectedMaxFullReadChars() throws Exception {
        Path importDir = tempDir.resolve("import");
        Files.createDirectories(importDir);
        Files.writeString(importDir.resolve("long.txt"), "x".repeat(500));
        var service = new ImportDocumentService(importDir, 100, 50);

        var result = service.readDocument("long.txt", 0, 10, true);

        assertEquals(100, result.limit(), "full read should cap at injected maxFullReadChars");
        assertEquals(100, result.content().length());
        assertTrue(result.truncated());
        assertEquals("100", result.nextOffset());
    }

    @Test
    @DisplayName("full=false 仍使用调用方显式 limit")
    void nonFullReadUsesExplicitLimit() throws Exception {
        Path importDir = tempDir.resolve("import");
        Files.createDirectories(importDir);
        Files.writeString(importDir.resolve("long.txt"), "x".repeat(500));
        var service = new ImportDocumentService(importDir, 100, 50);

        var result = service.readDocument("long.txt", 0, 5, false);

        assertEquals(5, result.limit(), "explicit limit should still win when not full read");
        assertEquals(5, result.content().length());
    }

    @Test
    @DisplayName("构造参数 100/50 被存储")
    void injectedLimitsAreStored() throws Exception {
        Path importDir = tempDir.resolve("import");
        Files.createDirectories(importDir);
        var service = new ImportDocumentService(importDir, 100, 50);

        assertEquals(100, service.maxFullReadChars());
        assertEquals(50, service.defaultLimit());
    }

    @Test
    @DisplayName("默认构造保留 30000/8000")
    void defaultConstructorKeepsDefaults() throws Exception {
        Path importDir = tempDir.resolve("import");
        Files.createDirectories(importDir);
        var service = new ImportDocumentService(importDir);

        assertEquals(30_000, service.maxFullReadChars());
        assertEquals(8_000, service.defaultLimit());
    }
}
