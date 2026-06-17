package com.gsim.webimport;

import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.MockResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * MediaWikiApiClient 测试 — 使用 MockWebServer，不访问外网。
 */
@DisplayName("MediaWikiApiClient")
class MediaWikiApiClientTest {

    private MockWebServer server;
    private MediaWikiApiClient apiClient;

    @BeforeEach
    void setUp() throws Exception {
        server = new MockWebServer();
        server.start();
        apiClient = new MediaWikiApiClient(
                "http://localhost:" + server.getPort() + "/api.php",
                5, "GSimulatorTest/0.1");
    }

    @AfterEach
    void tearDown() throws Exception {
        server.shutdown();
    }

    @Test
    @DisplayName("应通过 pageid 获取页面")
    void testGetPageByPageId() throws Exception {
        String json = """
                {
                    "parse": {
                        "title": "Test Page",
                        "pageid": 12345,
                        "text": {
                            "*": "<div><p>This is the parsed HTML content.</p></div>"
                        },
                        "links": [
                            {"title": "Linked Page 1"},
                            {"title": "Linked Page 2"}
                        ]
                    }
                }""";

        server.enqueue(new MockResponse()
                .setResponseCode(200)
                .setBody(json)
                .addHeader("Content-Type", "application/json"));

        MediaWikiApiClient.ApiPageResult result = apiClient.getPageByPageId(12345);

        assertEquals("Test Page", result.title());
        assertEquals(12345, result.pageId());
        assertTrue(result.html().contains("parsed HTML content"));
        assertEquals(2, result.links().size());
        assertEquals("Linked Page 1", result.links().get(0));
    }

    @Test
    @DisplayName("应通过 title 获取页面")
    void testGetPageByTitle() throws Exception {
        String json = """
                {
                    "parse": {
                        "title": "Arknights",
                        "pageid": 1,
                        "text": {
                            "*": "<p>Arknights is a mobile game.</p>"
                        },
                        "links": []
                    }
                }""";

        server.enqueue(new MockResponse()
                .setResponseCode(200)
                .setBody(json)
                .addHeader("Content-Type", "application/json"));

        MediaWikiApiClient.ApiPageResult result = apiClient.getPageByTitle("Arknights");

        assertEquals("Arknights", result.title());
        assertTrue(result.html().contains("Arknights is a mobile game"));
    }

    @Test
    @DisplayName("API 返回错误时应抛出异常")
    void testApiErrorThrowsException() {
        String json = """
                {
                    "error": {
                        "code": "missingtitle",
                        "info": "The page you specified doesn't exist."
                    }
                }""";

        server.enqueue(new MockResponse()
                .setResponseCode(200)
                .setBody(json)
                .addHeader("Content-Type", "application/json"));

        assertThrows(Exception.class, () -> apiClient.getPageByTitle("NonExistent"));
    }

    @Test
    @DisplayName("应获取页面列表")
    void testGetAllPages() throws Exception {
        String json = """
                {
                    "batchcomplete": "",
                    "query": {
                        "allpages": [
                            {"pageid": 1, "title": "Page A"},
                            {"pageid": 2, "title": "Page B"},
                            {"pageid": 3, "title": "Page C"}
                        ]
                    }
                }""";

        server.enqueue(new MockResponse()
                .setResponseCode(200)
                .setBody(json)
                .addHeader("Content-Type", "application/json"));

        var pages = apiClient.getAllPages(0, 10);

        assertEquals(3, pages.size());
        assertTrue(pages.contains("Page A"));
        assertTrue(pages.contains("Page B"));
        assertTrue(pages.contains("Page C"));
    }
}
