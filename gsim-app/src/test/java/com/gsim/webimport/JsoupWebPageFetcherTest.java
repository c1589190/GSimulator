package com.gsim.webimport;

import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.MockResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;

import static org.junit.jupiter.api.Assertions.*;

/**
 * JsoupWebPageFetcher 测试 — 使用 MockWebServer，不访问外网。
 */
@DisplayName("JsoupWebPageFetcher")
class JsoupWebPageFetcherTest {

    private MockWebServer server;
    private JsoupWebPageFetcher fetcher;

    @BeforeEach
    void setUp() throws Exception {
        server = new MockWebServer();
        server.start();
        fetcher = new JsoupWebPageFetcher(5, "GSimulatorTest/0.1", 5 * 1024 * 1024);
    }

    @AfterEach
    void tearDown() throws Exception {
        fetcher.close();
        server.shutdown();
    }

    @Test
    @DisplayName("应正确获取 HTML 页面")
    void testFetch_HtmlPage() throws Exception {
        String html = "<html><head><title>Test</title></head><body><p>Hello World</p></body></html>";

        server.enqueue(new MockResponse()
                .setResponseCode(200)
                .setBody(html)
                .addHeader("Content-Type", "text/html; charset=utf-8"));

        String result = fetcher.fetch("http://localhost:" + server.getPort() + "/test");
        assertTrue(result.contains("Hello World"));
        assertTrue(result.contains("<title>Test</title>"));
    }

    @Test
    @DisplayName("HTTP 错误状态码应抛出异常")
    void testFetch_HttpError() {
        server.enqueue(new MockResponse()
                .setResponseCode(404)
                .setBody("Not Found"));

        assertThrows(IOException.class, () ->
                fetcher.fetch("http://localhost:" + server.getPort() + "/notfound"));
    }

    @Test
    @DisplayName("不支持的内容类型应抛出异常")
    void testFetch_UnsupportedContentType() {
        server.enqueue(new MockResponse()
                .setResponseCode(200)
                .setBody("binary data")
                .addHeader("Content-Type", "application/pdf"));

        assertThrows(IOException.class, () ->
                fetcher.fetch("http://localhost:" + server.getPort() + "/file.pdf"));
    }

    @Test
    @DisplayName("内容过大应抛出异常")
    void testFetch_ContentTooLarge() {
        server.enqueue(new MockResponse()
                .setResponseCode(200)
                .setBody("x".repeat(100))
                .addHeader("Content-Type", "text/html")
                .addHeader("Content-Length", "99999999"));

        assertThrows(IOException.class, () ->
                fetcher.fetch("http://localhost:" + server.getPort() + "/large"));
    }

    @Test
    @DisplayName("fetcher name 应返回 okhttp-jsoup")
    void testName() {
        assertEquals("okhttp-jsoup", fetcher.name());
    }
}
