package com.gsim.webimport;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * HtmlTextExtractor 测试 — 使用 fixture HTML，不访问外网。
 */
@DisplayName("HtmlTextExtractor")
class HtmlTextExtractorTest {

    private final HtmlTextExtractor extractor = new HtmlTextExtractor();

    @Test
    @DisplayName("应正确提取 HTML title")
    void testExtractTitle() {
        String html = "<html><head><title>Test Page Title</title></head><body></body></html>";
        assertEquals("Test Page Title", extractor.extractTitle(html));
    }

    @Test
    @DisplayName("无 title 时应回退到 h1")
    void testExtractTitle_FallbackToH1() {
        String html = "<html><head></head><body><h1>Main Heading</h1></body></html>";
        assertEquals("Main Heading", extractor.extractTitle(html));
    }

    @Test
    @DisplayName("应移除 script 和 style 标签")
    void testExtractText_RemovesScriptAndStyle() {
        String html = """
                <html><head><title>Test</title></head><body>
                <script>console.log('should be removed');</script>
                <style>.test { color: red; }</style>
                <p>This is visible content.</p>
                </body></html>""";
        String result = extractor.extractText(html, "https://example.com");
        assertTrue(result.contains("This is visible content"));
        assertFalse(result.contains("console.log"));
        assertFalse(result.contains("color: red"));
    }

    @Test
    @DisplayName("应移除 nav、footer、header 元素")
    void testExtractText_RemovesNavFooterHeader() {
        String html = """
                <html><head><title>Test</title></head><body>
                <nav>Navigation menu</nav>
                <header>Site header</header>
                <p>Main content here.</p>
                <footer>Copyright 2024</footer>
                </body></html>""";
        String result = extractor.extractText(html, "https://example.com");
        assertTrue(result.contains("Main content here"));
        assertFalse(result.contains("Navigation menu"));
        assertFalse(result.contains("Site header"));
        assertFalse(result.contains("Copyright"));
    }

    @Test
    @DisplayName("应保留列表项")
    void testExtractText_PreservesListItems() {
        String html = """
                <html><head><title>List Test</title></head><body>
                <ul><li>Item A</li><li>Item B</li><li>Item C</li></ul>
                </body></html>""";
        String result = extractor.extractText(html, "https://example.com");
        assertTrue(result.contains("Item A"));
        assertTrue(result.contains("Item B"));
        assertTrue(result.contains("Item C"));
    }

    @Test
    @DisplayName("应保留 blockquote")
    void testExtractText_PreservesBlockquote() {
        String html = """
                <html><head><title>Quote Test</title></head><body>
                <blockquote>This is a quoted passage.</blockquote>
                </body></html>""";
        String result = extractor.extractText(html, "https://example.com");
        assertTrue(result.contains("This is a quoted passage"));
    }

    @Test
    @DisplayName("应保留 pre 标签内容")
    void testExtractText_PreservesPre() {
        String html = """
                <html><head><title>Code Test</title></head><body>
                <pre>function hello() {
            return "world";
        }</pre>
                </body></html>""";
        String result = extractor.extractText(html, "https://example.com");
        assertTrue(result.contains("function hello()"));
    }

    @Test
    @DisplayName("应提取表格文本")
    void testExtractText_PreservesTable() {
        String html = """
                <html><head><title>Table Test</title></head><body>
                <table>
                <tr><th>Name</th><th>Value</th></tr>
                <tr><td>Alice</td><td>100</td></tr>
                <tr><td>Bob</td><td>200</td></tr>
                </table>
                </body></html>""";
        String result = extractor.extractText(html, "https://example.com");
        assertTrue(result.contains("Name"));
        assertTrue(result.contains("Alice"));
        assertTrue(result.contains("Bob"));
    }

    @Test
    @DisplayName("应提取内部链接")
    void testExtractInternalLinks() {
        String html = """
                <html><head><title>Links</title></head><body>
                <a href="/page1">Page 1</a>
                <a href="/page2">Page 2</a>
                <a href="https://external.com/page">External</a>
                </body></html>""";
        var links = extractor.extractInternalLinks(html, "https://example.com");
        assertTrue(links.size() >= 2);
        assertTrue(links.stream().anyMatch(l -> l.contains("page1")));
        assertTrue(links.stream().anyMatch(l -> l.contains("page2")));
    }

    @Test
    @DisplayName("空 HTML 应返回空字符串")
    void testExtractText_EmptyHtml() {
        String result = extractor.extractText("", "https://example.com");
        assertEquals("", result);
    }

    @Test
    @DisplayName("应保留 h1-h6 标题")
    void testExtractText_PreservesHeadings() {
        String html = """
                <html><head><title>Heading Test</title></head><body>
                <h1>Chapter 1</h1>
                <p>Some text.</p>
                <h2>Section 1.1</h2>
                <p>More text.</p>
                </body></html>""";
        String result = extractor.extractText(html, "https://example.com");
        assertTrue(result.contains("Chapter 1"));
        assertTrue(result.contains("Section 1.1"));
        assertTrue(result.contains("Some text"));
    }

    @Test
    @DisplayName("应保留 hidden 属性元素中的文本")
    void testExtractText_PreservesHiddenAttributeText() {
        String html = """
                <html><head><title>Hidden Test</title></head><body>
                <div hidden>This content is hidden but should be extracted.</div>
                <p>Visible content.</p>
                </body></html>""";
        String result = extractor.extractText(html, "https://example.com");
        assertTrue(result.contains("This content is hidden but should be extracted"));
        assertTrue(result.contains("Visible content"));
    }

    @Test
    @DisplayName("应保留 display:none 元素中的文本")
    void testExtractText_PreservesDisplayNoneText() {
        String html = """
                <html><head><title>Display None Test</title></head><body>
                <div style="display:none">This is collapsed content.</div>
                <div style="display: none">This is also collapsed.</div>
                <p>Visible content.</p>
                </body></html>""";
        String result = extractor.extractText(html, "https://example.com");
        assertTrue(result.contains("This is collapsed content"));
        assertTrue(result.contains("This is also collapsed"));
        assertTrue(result.contains("Visible content"));
    }

    @Test
    @DisplayName("应仍然删除 script 和 style 标签")
    void testExtractText_StillRemovesScriptAndStyle() {
        String html = """
                <html><head><title>Script Test</title></head><body>
                <script>console.log('should be removed');</script>
                <style>.test { color: red; }</style>
                <p>This is visible content.</p>
                </body></html>""";
        String result = extractor.extractText(html, "https://example.com");
        assertTrue(result.contains("This is visible content"));
        assertFalse(result.contains("console.log"));
        assertFalse(result.contains("color: red"));
    }

    @Test
    @DisplayName("应仍然删除 nav/footer/header/aside")
    void testExtractText_StillRemovesNavFooterHeaderAside() {
        String html = """
                <html><head><title>Nav Test</title></head><body>
                <nav>Navigation menu</nav>
                <header>Site header</header>
                <aside>Sidebar content</aside>
                <p>Main content here.</p>
                <footer>Copyright 2024</footer>
                </body></html>""";
        String result = extractor.extractText(html, "https://example.com");
        assertTrue(result.contains("Main content here"));
        assertFalse(result.contains("Navigation menu"));
        assertFalse(result.contains("Site header"));
        assertFalse(result.contains("Sidebar content"));
        assertFalse(result.contains("Copyright"));
    }
}
