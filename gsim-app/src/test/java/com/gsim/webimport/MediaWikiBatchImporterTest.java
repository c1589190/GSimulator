package com.gsim.webimport;

import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.MockResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * MediaWikiBatchImporter 测试 — 使用 MockWebServer，不访问外网。
 */
@DisplayName("MediaWikiBatchImporter")
class MediaWikiBatchImporterTest {

    private MockWebServer server;
    private MediaWikiApiClient apiClient;
    private WebImportFileWriter fileWriter;
    private HtmlTextExtractor textExtractor;
    private RateLimiter rateLimiter;

    @TempDir
    Path tempDir;

    @BeforeEach
    void setUp() throws Exception {
        server = new MockWebServer();
        server.start();

        String apiUrl = "http://localhost:" + server.getPort() + "/api.php";
        apiClient = new MediaWikiApiClient(apiUrl, 5, "GSimulatorTest/0.1");
        fileWriter = new WebImportFileWriter(tempDir.resolve("import"));
        textExtractor = new HtmlTextExtractor();
        rateLimiter = new RateLimiter(0); // 测试中不延迟
    }

    @AfterEach
    void tearDown() throws Exception {
        server.shutdown();
    }

    @Test
    @DisplayName("应批量获取 allpages 并写入文件")
    void testImportAllPages() throws Exception {
        // 第一步：allpages API 返回页面列表
        String allpagesJson = """
                {
                    "query": {
                        "allpages": [
                            {"pageid": 1, "title": "Page One"},
                            {"pageid": 2, "title": "Page Two"}
                        ]
                    }
                }""";
        server.enqueue(new MockResponse()
                .setResponseCode(200)
                .setBody(allpagesJson)
                .addHeader("Content-Type", "application/json"));

        // 第二步：getPageByTitle("Page One") 返回 HTML
        String pageOneJson = """
                {
                    "parse": {
                        "title": "Page One",
                        "pageid": 1,
                        "text": {
                            "*": "<div><p>Content of page one.</p></div>"
                        },
                        "links": []
                    }
                }""";
        server.enqueue(new MockResponse()
                .setResponseCode(200)
                .setBody(pageOneJson)
                .addHeader("Content-Type", "application/json"));

        // 第三步：getPageByTitle("Page Two") 返回 HTML
        String pageTwoJson = """
                {
                    "parse": {
                        "title": "Page Two",
                        "pageid": 2,
                        "text": {
                            "*": "<div><p>Content of page two.</p></div>"
                        },
                        "links": []
                    }
                }""";
        server.enqueue(new MockResponse()
                .setResponseCode(200)
                .setBody(pageTwoJson)
                .addHeader("Content-Type", "application/json"));

        MediaWikiBatchImporter importer = new MediaWikiBatchImporter(
                apiClient, fileWriter, textExtractor, rateLimiter,
                "prts.wiki", "https://prts.wiki");

        MediaWikiBatchImporter.BatchImportResult result =
                importer.importAllPages("", 2, "prts/test");

        assertEquals(2, result.pagesFetched());
        assertEquals(2, result.filesWritten());
        assertEquals(2, result.writtenFiles().size());
        assertTrue(result.failedTitles().isEmpty());
        assertTrue(result.errors().isEmpty());

        // 验证文件存在
        for (Path p : result.writtenFiles()) {
            assertTrue(p.toFile().exists(), "Expected file to exist: " + p);
            String content = java.nio.file.Files.readString(p);
            assertTrue(content.contains("Content of page"), "File should contain page content");
            assertTrue(content.contains("Source URL:"), "File should contain metadata");
            assertTrue(content.contains("prts.wiki"), "File should contain host");
            assertTrue(p.toString().contains("prts/test"), "File should be in subdir");
        }
    }

    @Test
    @DisplayName("应跳过排除的命名空间页面")
    void testSkipExcludedPages() throws Exception {
        String allpagesJson = """
                {
                    "query": {
                        "allpages": [
                            {"pageid": 1, "title": "Special:Version"},
                            {"pageid": 2, "title": "Normal Page"},
                            {"pageid": 3, "title": "File:SomeImage"}
                        ]
                    }
                }""";
        server.enqueue(new MockResponse()
                .setResponseCode(200)
                .setBody(allpagesJson)
                .addHeader("Content-Type", "application/json"));

        // 只有 Normal Page 会被获取
        String normalPageJson = """
                {
                    "parse": {
                        "title": "Normal Page",
                        "pageid": 2,
                        "text": {
                            "*": "<p>Normal content.</p>"
                        },
                        "links": []
                    }
                }""";
        server.enqueue(new MockResponse()
                .setResponseCode(200)
                .setBody(normalPageJson)
                .addHeader("Content-Type", "application/json"));

        MediaWikiBatchImporter importer = new MediaWikiBatchImporter(
                apiClient, fileWriter, textExtractor, rateLimiter,
                "prts.wiki", "https://prts.wiki");

        MediaWikiBatchImporter.BatchImportResult result =
                importer.importAllPages("", 5, "");

        // Special: 和 File: 应被跳过，只抓取 Normal Page
        assertEquals(1, result.pagesFetched()); // 跳过的页面不计入 fetched
        assertEquals(1, result.filesWritten()); // 只成功写入 1 个
        assertTrue(result.failedTitles().isEmpty()); // 被跳过的也不计入 failed
    }

    @Test
    @DisplayName("maxPages 应限制总抓取数")
    void testMaxPagesLimit() throws Exception {
        String allpagesJson = """
                {
                    "query": {
                        "allpages": [
                            {"pageid": 1, "title": "A"},
                            {"pageid": 2, "title": "B"},
                            {"pageid": 3, "title": "C"},
                            {"pageid": 4, "title": "D"}
                        ]
                    }
                }""";
        server.enqueue(new MockResponse()
                .setResponseCode(200)
                .setBody(allpagesJson)
                .addHeader("Content-Type", "application/json"));

        // 为每个页面准备响应
        for (int i = 0; i < 2; i++) {
            server.enqueue(new MockResponse()
                    .setResponseCode(200)
                    .setBody("""
                            {
                                "parse": {
                                    "title": "Page",
                                    "pageid": 1,
                                    "text": {"*": "<p>x</p>"},
                                    "links": []
                                }
                            }""")
                    .addHeader("Content-Type", "application/json"));
        }

        MediaWikiBatchImporter importer = new MediaWikiBatchImporter(
                apiClient, fileWriter, textExtractor, rateLimiter,
                "prts.wiki", "https://prts.wiki");

        MediaWikiBatchImporter.BatchImportResult result =
                importer.importAllPages("", 2, "");

        assertEquals(2, result.pagesFetched());
        assertEquals(2, result.filesWritten());
    }
}
