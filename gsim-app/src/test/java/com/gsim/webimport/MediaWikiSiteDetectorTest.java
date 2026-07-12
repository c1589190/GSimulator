package com.gsim.webimport;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * MediaWikiSiteDetector 测试 — 使用 mock 响应数据，不访问外网。
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
        assertTrue(MediaWikiCrawler.shouldExcludeTitle("Special:Random"));
        assertTrue(MediaWikiCrawler.shouldExcludeTitle("User:Admin"));
        assertTrue(MediaWikiCrawler.shouldExcludeTitle("File:Image.png"));
        assertTrue(MediaWikiCrawler.shouldExcludeTitle("Category:Items"));
        assertTrue(MediaWikiCrawler.shouldExcludeTitle("Template:Infobox"));
        assertTrue(MediaWikiCrawler.shouldExcludeTitle("Help:Contents"));
        assertTrue(MediaWikiCrawler.shouldExcludeTitle("Talk:Discussion"));
        assertTrue(MediaWikiCrawler.shouldExcludeTitle(""));
        assertTrue(MediaWikiCrawler.shouldExcludeTitle(null));
    }

    @Test
    @DisplayName("正常页面标题不应被排除")
    void testShouldNotExclude_NormalPages() {
        assertFalse(MediaWikiCrawler.shouldExcludeTitle("明日方舟"));
        assertFalse(MediaWikiCrawler.shouldExcludeTitle("Arknights"));
        assertFalse(MediaWikiCrawler.shouldExcludeTitle("Operator_List"));
        assertFalse(MediaWikiCrawler.shouldExcludeTitle("Main_Page"));
    }

    @Test
    @DisplayName("MediaWiki URL 提取标题 — /wiki/ 路径")
    void testExtractTitleFromUrl_WikiPath() {
        MediaWikiCrawler crawler = new MediaWikiCrawler(null, null, "example.com", "https://example.com");

        assertEquals("Arknights", crawler.extractTitleFromUrl("https://example.com/wiki/Arknights"));
        assertEquals("Operator_List", crawler.extractTitleFromUrl("https://example.com/wiki/Operator_List"));
        assertNull(crawler.extractTitleFromUrl("https://example.com/"));
    }

    @Test
    @DisplayName("MediaWiki URL 解析 — title= 参数")
    void testParsePageIdentifier_Title() {
        MediaWikiCrawler crawler = new MediaWikiCrawler(null, null, "example.com", "https://example.com");

        var ident = crawler.parsePageIdentifier("https://example.com/index.php?title=Test_Page");
        assertNotNull(ident);
        assertEquals(MediaWikiCrawler.PageIdentifier.IdType.TITLE, ident.type());
        assertEquals("Test_Page", ident.stringValue());
    }

    @Test
    @DisplayName("MediaWiki URL 解析 — curid= 参数")
    void testParsePageIdentifier_Curid() {
        MediaWikiCrawler crawler = new MediaWikiCrawler(null, null, "example.com", "https://example.com");

        var ident = crawler.parsePageIdentifier("https://example.com/index.php?curid=12345");
        assertNotNull(ident);
        assertEquals(MediaWikiCrawler.PageIdentifier.IdType.CURID, ident.type());
        assertEquals(12345, ident.numericValue());
    }

    @Test
    @DisplayName("MediaWiki URL 解析 — pageid= 参数")
    void testParsePageIdentifier_Pageid() {
        MediaWikiCrawler crawler = new MediaWikiCrawler(null, null, "example.com", "https://example.com");

        var ident = crawler.parsePageIdentifier("https://example.com/index.php?pageid=67890");
        assertNotNull(ident);
        assertEquals(MediaWikiCrawler.PageIdentifier.IdType.PAGEID, ident.type());
        assertEquals(67890, ident.numericValue());
    }

    @Test
    @DisplayName("PageIdentifier.forCurid 应创建 CURID 类型")
    void testPageIdentifier_ForCurid() {
        var ident = MediaWikiCrawler.PageIdentifier.forCurid(42);
        assertEquals(MediaWikiCrawler.PageIdentifier.IdType.CURID, ident.type());
        assertEquals(42, ident.numericValue());
        assertEquals("42", ident.stringValue());
    }

    @Test
    @DisplayName("PageIdentifier.forPageid 应创建 PAGEID 类型")
    void testPageIdentifier_ForPageid() {
        var ident = MediaWikiCrawler.PageIdentifier.forPageid(99);
        assertEquals(MediaWikiCrawler.PageIdentifier.IdType.PAGEID, ident.type());
        assertEquals(99, ident.numericValue());
    }

    @Test
    @DisplayName("PageIdentifier.forTitle 应创建 TITLE 类型")
    void testPageIdentifier_ForTitle() {
        var ident = MediaWikiCrawler.PageIdentifier.forTitle("Arknights");
        assertEquals(MediaWikiCrawler.PageIdentifier.IdType.TITLE, ident.type());
        assertEquals("Arknights", ident.stringValue());
    }

    @Test
    @DisplayName("curid/pageid 类型不应被排除")
    void testCuridPageidNotExcluded() {
        var curidIdent = MediaWikiCrawler.PageIdentifier.forCurid(123);
        assertFalse(MediaWikiCrawler.shouldExcludeTitle(curidIdent.titleForExclusionCheck()));

        var pageidIdent = MediaWikiCrawler.PageIdentifier.forPageid(456);
        assertFalse(MediaWikiCrawler.shouldExcludeTitle(pageidIdent.titleForExclusionCheck()));
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
