package com.gsim.webimport;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * UrlNormalizer 测试 — 使用 fixture 数据，不访问外网。
 */
@DisplayName("UrlNormalizer")
class UrlNormalizerTest {

    private final UrlNormalizer normalizer = new UrlNormalizer();

    @Test
    @DisplayName("应正确规范化标准 URL")
    void testNormalize_StandardUrl() {
        String result = normalizer.normalize("https://example.com/path/page.html");
        assertEquals("https://example.com/path/page.html", result);
    }

    @Test
    @DisplayName("应移除 URL fragment")
    void testNormalize_RemovesFragment() {
        String result = normalizer.normalize("https://example.com/page#section1");
        assertEquals("https://example.com/page", result);
    }

    @Test
    @DisplayName("应移除末尾斜杠（非根路径）")
    void testNormalize_RemovesTrailingSlash() {
        String result = normalizer.normalize("https://example.com/page/");
        assertEquals("https://example.com/page", result);
    }

    @Test
    @DisplayName("应保留根路径斜杠")
    void testNormalize_KeepsRootSlash() {
        String result = normalizer.normalize("https://example.com/");
        assertEquals("https://example.com/", result);
    }

    @Test
    @DisplayName("应小写化 scheme 和 host")
    void testNormalize_LowercaseSchemeAndHost() {
        String result = normalizer.normalize("HTTPS://EXAMPLE.COM/Page");
        assertEquals("https://example.com/Page", result);
    }

    @Test
    @DisplayName("应移除 www. 前缀")
    void testNormalize_RemovesWwwPrefix() {
        String result = normalizer.normalize("https://www.example.com/page");
        assertEquals("https://example.com/page", result);
    }

    @Test
    @DisplayName("应移除默认 HTTP 端口 80")
    void testNormalize_RemovesDefaultHttpPort() {
        String result = normalizer.normalize("http://example.com:80/page");
        assertEquals("http://example.com/page", result);
    }

    @Test
    @DisplayName("应移除默认 HTTPS 端口 443")
    void testNormalize_RemovesDefaultHttpsPort() {
        String result = normalizer.normalize("https://example.com:443/page");
        assertEquals("https://example.com/page", result);
    }

    @Test
    @DisplayName("应保留非默认端口")
    void testNormalize_KeepsNonDefaultPort() {
        String result = normalizer.normalize("http://example.com:8080/page");
        assertEquals("http://example.com:8080/page", result);
    }

    @Test
    @DisplayName("应拒绝非 HTTP 协议")
    void testNormalize_RejectsNonHttp() {
        assertNull(normalizer.normalize("ftp://example.com/file"));
        assertNull(normalizer.normalize("javascript:void(0)"));
    }

    @Test
    @DisplayName("应拒绝空 URL")
    void testNormalize_RejectsEmpty() {
        assertNull(normalizer.normalize(""));
        assertNull(normalizer.normalize(null));
    }

    @Test
    @DisplayName("应正确提取 host")
    void testExtractHost() {
        assertEquals("example.com", normalizer.extractHost("https://www.example.com/page"));
        assertEquals("example.com", normalizer.extractHost("http://example.com/path"));
        assertEquals("sub.example.com", normalizer.extractHost("https://sub.example.com/"));
    }

    @Test
    @DisplayName("应正确判断同域名")
    void testIsSameHost() {
        assertTrue(normalizer.isSameHost(
                "https://example.com/page1",
                "https://example.com/page2"));
        assertTrue(normalizer.isSameHost(
                "https://www.example.com/a",
                "https://example.com/b"));
        assertFalse(normalizer.isSameHost(
                "https://example.com/page",
                "https://other.com/page"));
    }

    @Test
    @DisplayName("应正确判断 URL 与 host 匹配")
    void testIsHostMatch() {
        assertTrue(normalizer.isHostMatch("https://example.com/page", "example.com"));
        assertTrue(normalizer.isHostMatch("https://www.example.com/page", "example.com"));
        assertFalse(normalizer.isHostMatch("https://example.com/page", "other.com"));
    }

    @Test
    @DisplayName("应正确解析相对 URL")
    void testResolve_RelativeUrl() {
        String result = normalizer.resolve("https://example.com/dir/page.html", "../other.html");
        assertEquals("https://example.com/other.html", result);
    }

    @Test
    @DisplayName("应正确解析绝对 URL")
    void testResolve_AbsoluteUrl() {
        String result = normalizer.resolve("https://example.com/dir/page.html", "https://other.com/page");
        assertEquals("https://other.com/page", result);
    }

    @Test
    @DisplayName("应正确判断需跳过的 URL")
    void testShouldSkip() {
        assertTrue(normalizer.shouldSkip("javascript:void(0)"));
        assertTrue(normalizer.shouldSkip("mailto:test@example.com"));
        assertTrue(normalizer.shouldSkip("tel:+1234567890"));
        assertTrue(normalizer.shouldSkip("#anchor"));
        assertTrue(normalizer.shouldSkip(""));
        assertTrue(normalizer.shouldSkip(null));
        assertFalse(normalizer.shouldSkip("https://example.com/page"));
    }
}
