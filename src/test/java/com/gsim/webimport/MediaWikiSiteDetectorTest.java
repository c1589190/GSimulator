package com.gsim.webimport;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * MediaWikiSiteDetector 测试 — 使用 mock 响应数据，不访问外网。
 * 实际 HTTP 请求测试通过 MockWebServer 在 MediaWikiApiClientTest 中完成。
 */
@DisplayName("MediaWikiSiteDetector")
class MediaWikiSiteDetectorTest {

    @Test
    @DisplayName("DetectionResult.notMediaWiki 应正确设置字段")
    void testDetectionResult_NotMediaWiki() {
        var result = MediaWikiSiteDetector.DetectionResult.notMediaWiki();
        assertFalse(result.isMediaWiki());
        assertEquals("", result.apiUrl());
        assertEquals("", result.siteName());
        assertEquals("", result.generator());
    }

    @Test
    @DisplayName("DetectionResult.mediaWiki 应正确设置字段")
    void testDetectionResult_MediaWiki() {
        var result = MediaWikiSiteDetector.DetectionResult.mediaWiki(
                "https://example.com/api.php", "Test Wiki", "MediaWiki 1.39");
        assertTrue(result.isMediaWiki());
        assertEquals("https://example.com/api.php", result.apiUrl());
        assertEquals("Test Wiki", result.siteName());
        assertEquals("MediaWiki 1.39", result.generator());
    }

    @Test
    @DisplayName("MediaWiki 排除列表应正确过滤")
    void testShouldExclude() {
        assertTrue(MediaWikiCrawler.shouldExclude("Special:Random"));
        assertTrue(MediaWikiCrawler.shouldExclude("User:Admin"));
        assertTrue(MediaWikiCrawler.shouldExclude("File:Image.png"));
        assertTrue(MediaWikiCrawler.shouldExclude("Category:Items"));
        assertTrue(MediaWikiCrawler.shouldExclude("Template:Infobox"));
        assertTrue(MediaWikiCrawler.shouldExclude("Help:Contents"));
        assertTrue(MediaWikiCrawler.shouldExclude("Talk:Discussion"));
        assertTrue(MediaWikiCrawler.shouldExclude(""));
        assertTrue(MediaWikiCrawler.shouldExclude(null));
    }

    @Test
    @DisplayName("正常页面标题不应被排除")
    void testShouldNotExclude_NormalPages() {
        assertFalse(MediaWikiCrawler.shouldExclude("明日方舟"));
        assertFalse(MediaWikiCrawler.shouldExclude("Arknights"));
        assertFalse(MediaWikiCrawler.shouldExclude("Operator_List"));
        assertFalse(MediaWikiCrawler.shouldExclude("Main_Page"));
    }

    @Test
    @DisplayName("MediaWiki URL 提取标题")
    void testExtractTitleFromUrl() {
        MediaWikiCrawler crawler = new MediaWikiCrawler(null, null, "example.com", "https://example.com");

        assertEquals("Arknights", crawler.extractTitleFromUrl("https://example.com/wiki/Arknights"));
        assertEquals("Operator_List", crawler.extractTitleFromUrl("https://example.com/wiki/Operator_List"));
        assertEquals("Test_Page", crawler.extractTitleFromUrl("https://example.com/index.php?title=Test_Page"));
        assertNull(crawler.extractTitleFromUrl("https://example.com/"));
    }

    @Test
    @DisplayName("MediaWiki title 转 URL")
    void testTitleToUrl() {
        MediaWikiCrawler crawler = new MediaWikiCrawler(null, null, "example.com", "https://example.com");

        String url = crawler.titleToUrl("Arknights");
        assertTrue(url.contains("Arknights"));
        assertTrue(url.startsWith("https://example.com/wiki/"));
    }
}
