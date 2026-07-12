package com.gsim.webimport;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * WebImportFileWriter 测试 — 使用临时目录，不访问外网。
 */
@DisplayName("WebImportFileWriter")
class WebImportFileWriterTest {

    @TempDir
    Path tempDir;

    @Test
    @DisplayName("应正确写入文件并包含完整元数据")
    void testWrite_FileWrittenWithMetadata() throws Exception {
        WebImportFileWriter writer = new WebImportFileWriter(tempDir);

        CrawledPage page = CrawledPage.builder("https://example.com/wiki/Test_Page")
                .host("example.com")
                .title("Test Page")
                .cleanedText("This is the extracted content of the test page.")
                .crawlerName("generic")
                .fetchedAt(Instant.parse("2024-01-15T10:30:00Z"))
                .depth(1)
                .internalLinks(List.of("https://example.com/wiki/Page2"))
                .build();

        Path filePath = writer.write(page);

        assertTrue(Files.exists(filePath));
        String content = Files.readString(filePath, StandardCharsets.UTF_8);

        // 验证元数据
        assertTrue(content.contains("# Test Page"));
        assertTrue(content.contains("Source URL: https://example.com/wiki/Test_Page"));
        assertTrue(content.contains("Site: example.com"));
        assertTrue(content.contains("Crawler: generic"));
        assertTrue(content.contains("Collection Hint: world_lore"));
        assertTrue(content.contains("Tags: web,example.com"));

        // 验证正文
        assertTrue(content.contains("This is the extracted content"));

        // 验证文件路径结构
        assertTrue(filePath.toString().contains("web"));
        assertTrue(filePath.toString().contains("example.com"));
        assertTrue(filePath.toString().endsWith(".txt"));
    }

    @Test
    @DisplayName("应自动创建 web import 目录")
    void testWrite_CreatesWebImportDir() throws Exception {
        Path importDir = tempDir.resolve("import");
        WebImportFileWriter writer = new WebImportFileWriter(importDir);
        writer.ensureDir();

        assertTrue(Files.exists(importDir.resolve("web")));
        assertTrue(Files.isDirectory(importDir.resolve("web")));
    }

    @Test
    @DisplayName("文件名应包含哈希以区分相同标题")
    void testWrite_DifferentHashForDifferentUrls() throws Exception {
        WebImportFileWriter writer = new WebImportFileWriter(tempDir);

        CrawledPage page1 = CrawledPage.builder("https://example.com/wiki/Page1")
                .host("example.com")
                .title("Same Title")
                .cleanedText("Content 1")
                .build();

        CrawledPage page2 = CrawledPage.builder("https://example.com/wiki/Page2")
                .host("example.com")
                .title("Same Title")
                .cleanedText("Content 2")
                .build();

        Path path1 = writer.write(page1);
        Path path2 = writer.write(page2);

        // 文件名应不同（因为 hash 不同）
        assertNotEquals(path1.getFileName().toString(), path2.getFileName().toString());
    }

    @Test
    @DisplayName("标题为空时应使用 untitled")
    void testWrite_EmptyTitleUsesUntitled() throws Exception {
        WebImportFileWriter writer = new WebImportFileWriter(tempDir);

        CrawledPage page = CrawledPage.builder("https://example.com/page")
                .host("example.com")
                .title("")
                .cleanedText("Content")
                .build();

        Path filePath = writer.write(page);
        assertTrue(filePath.getFileName().toString().startsWith("untitled-"));
    }

    @Test
    @DisplayName("应清理文件名中的不安全字符")
    void testWrite_SanitizesFilename() throws Exception {
        WebImportFileWriter writer = new WebImportFileWriter(tempDir);

        CrawledPage page = CrawledPage.builder("https://example.com/page")
                .host("example.com")
                .title("File: Test / Wiki? Query*Name")
                .cleanedText("Content")
                .build();

        Path filePath = writer.write(page);
        String filename = filePath.getFileName().toString();

        // 不应包含不安全字符
        assertFalse(filename.contains("/"));
        assertFalse(filename.contains(":"));
        assertFalse(filename.contains("?"));
        assertFalse(filename.contains("*"));
        assertFalse(filename.contains(" "));  // spaces replaced by hyphens
    }
}
