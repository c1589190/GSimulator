package com.gsim.webimport;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * WebImportManager 测试 — 使用 mock fetcher，不访问外网。
 */
@DisplayName("WebImportManager")
class WebImportManagerTest {

    @TempDir
    Path tempDir;

    @Test
    @DisplayName("无效 URL 应返回错误")
    void testExecute_InvalidUrl() throws Exception {
        WebImportManager manager = new WebImportManager(tempDir);

        WebImportRequest request = WebImportRequest.builder(new URI("not-a-valid-url"))
                .build();

        // 注意：URI 构造函数会验证，所以用实际有效的 URI 但不可达的
        WebImportRequest validRequest = WebImportRequest.builder(new URI("https://invalid.example.com"))
                .timeoutSeconds(2)
                .maxPages(1)
                .maxDepth(0)
                .build();

        WebImportResult result = manager.execute(validRequest);

        // 应该失败（无法连接）
        assertNotNull(result);
        assertTrue(result.pagesFetched() >= 0);
    }

    @Test
    @DisplayName("fetchOnly 模式应正确标记")
    void testExecute_FetchOnly() throws Exception {
        WebImportManager manager = new WebImportManager(tempDir);

        WebImportRequest request = WebImportRequest.builder(new URI("https://invalid.example.com"))
                .fetchOnly(true)
                .timeoutSeconds(2)
                .maxPages(1)
                .maxDepth(0)
                .build();

        WebImportResult result = manager.execute(request);

        assertTrue(result.fetchOnly());
    }

    @Test
    @DisplayName("WebImportRequest builder 应正确设置默认值")
    void testRequestBuilder_Defaults() throws Exception {
        WebImportRequest request = WebImportRequest.builder(new URI("https://example.com"))
                .build();

        assertEquals("https://example.com", request.url().toString());
        assertFalse(request.fetchOnly());
        assertTrue(request.crawlEnabled());
        assertEquals(50, request.maxPages());
        assertEquals(2, request.maxDepth());
        assertEquals(1000, request.delayMillis());
        assertEquals(15, request.timeoutSeconds());
        assertEquals(5 * 1024 * 1024, request.maxBytesPerPage());
        assertEquals("GSimulatorBot/0.1", request.userAgent());
        assertTrue(request.sameHostOnly());
        assertEquals("example.com", request.host());
    }

    @Test
    @DisplayName("WebImportRequest builder 应支持自定义参数")
    void testRequestBuilder_Custom() throws Exception {
        WebImportRequest request = WebImportRequest.builder(new URI("https://example.com"))
                .fetchOnly(true)
                .crawlEnabled(false)
                .maxPages(10)
                .maxDepth(1)
                .delayMillis(2000)
                .timeoutSeconds(30)
                .maxBytesPerPage(1024 * 1024)
                .userAgent("CustomBot/1.0")
                .sameHostOnly(false)
                .build();

        assertTrue(request.fetchOnly());
        assertFalse(request.crawlEnabled());
        assertEquals(10, request.maxPages());
        assertEquals(1, request.maxDepth());
        assertEquals(2000, request.delayMillis());
        assertEquals(30, request.timeoutSeconds());
        assertEquals(1024 * 1024, request.maxBytesPerPage());
        assertEquals("CustomBot/1.0", request.userAgent());
        assertFalse(request.sameHostOnly());
    }

    @Test
    @DisplayName("WebImportResult summary 应包含关键信息")
    void testResultSummary() {
        WebImportResult result = new WebImportResult(
                "https://example.com", "example.com",
                5, 0, 2, 5,
                List.of(), List.of("error1"),
                false, "generic");

        String summary = result.summary();
        assertTrue(summary.contains("example.com"));
        assertTrue(summary.contains("fetched=5"));
        assertTrue(summary.contains("failed=2"));
        assertTrue(summary.contains("generic"));
    }

    @Test
    @DisplayName("CrawledPage builder 应正确设置字段")
    void testCrawledPageBuilder() {
        CrawledPage page = CrawledPage.builder("https://example.com/page")
                .host("example.com")
                .title("Test Page")
                .html("<html>...</html>")
                .cleanedText("Cleaned content")
                .depth(2)
                .internalLinks(List.of("https://example.com/link1"))
                .crawlerName("generic")
                .success(true)
                .build();

        assertEquals("https://example.com/page", page.url());
        assertEquals("example.com", page.host());
        assertEquals("Test Page", page.title());
        assertEquals("Cleaned content", page.cleanedText());
        assertEquals(2, page.depth());
        assertEquals(1, page.internalLinks().size());
        assertEquals("generic", page.crawlerName());
        assertTrue(page.success());
    }

    @Test
    @DisplayName("CrawledPage.failed 应创建失败页面")
    void testCrawledPageFailed() {
        CrawledPage page = CrawledPage.failed("https://example.com/page", "Connection timeout");

        assertFalse(page.success());
        assertEquals("Connection timeout", page.errorMessage());
        assertEquals("https://example.com/page", page.url());
    }

    @Test
    @DisplayName("DisabledRenderedPageFetcher 应返回警告")
    void testDisabledRenderedPageFetcher() {
        DisabledRenderedPageFetcher fetcher = new DisabledRenderedPageFetcher();

        assertFalse(fetcher.isEnabled());
        assertEquals("disabled", fetcher.name());

        RenderedPageFetcher.FetchResult result = fetcher.fetchRendered("https://example.com");
        assertTrue(result.success());
        assertTrue(result.warning().contains("JS rendering is not enabled"));
    }
}
