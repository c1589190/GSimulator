package com.gsim.tool;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * WikiSearchTool / LocalFileSearchService 测试 — 使用临时文件，不访问外网。
 */
@DisplayName("WikiSearchTool")
class WikiSearchToolTest {

    @TempDir
    Path tempDir;

    private WikiSearchTool tool;
    private ToolRegistry registry;

    @BeforeEach
    void setUp() throws Exception {
        // 创建模拟的 wiki txt 文件
        Path prtsDir = tempDir.resolve("web").resolve("prts.wiki");
        Files.createDirectories(prtsDir);

        Files.writeString(prtsDir.resolve("page-a-abc12345.txt"), """
                # 罗德岛
                Source URL: https://prts.wiki/w/罗德岛
                Fetched At: 2025-01-01 12:00:00
                Site: prts.wiki
                Crawler: mediawiki-batch-allpages
                Collection Hint: world_lore
                Tags: web,prts.wiki
                ---
                罗德岛制药公司，简称罗德岛，是一家致力于矿石病研究与治疗的跨国组织。
                总部位于移动城市罗德岛号上，在泰拉大陆各地设有办事处。
                罗德岛拥有强大的武装力量，包括精英干员队伍。
                主要领导者：阿米娅、凯尔希、博士。
                """);

        Files.writeString(prtsDir.resolve("page-b-def67890.txt"), """
                # 年
                Source URL: https://prts.wiki/w/年
                Fetched At: 2025-01-01 12:00:00
                Site: prts.wiki
                Crawler: mediawiki-batch-allpages
                Collection Hint: world_lore
                Tags: web,prts.wiki
                ---
                重定向到：年的个人档案页面。
                年是罗德岛的干员之一。
                """);

        Files.writeString(prtsDir.resolve("page-c-ghi11111.txt"), """
                # 陨石事件
                Source URL: https://prts.wiki/w/陨石事件
                Fetched At: 2025-01-01 12:00:00
                Site: prts.wiki
                Crawler: mediawiki-batch-allpages
                Collection Hint: world_lore
                Tags: web,prts.wiki
                ---
                泰拉大陆历史上发生过多起陨石坠落事件，这些陨石被称为"源石"。
                源石的降临改变了整个世界的格局，也带来了矿石病。
                """);

        LocalFileSearchService searchService = new LocalFileSearchService(prtsDir);
        tool = new WikiSearchTool(searchService);

        registry = new ToolRegistry();
        registry.register(tool);
    }

    @Test
    @DisplayName("应通过关键词搜索到匹配的页面")
    void testSearchByKeyword() {
        ToolCall call = new ToolCall("wiki_search", Map.of("keyword", "罗德岛"));
        ToolResult result = tool.execute(call);

        assertTrue(result.success());
        assertFalse(result.items().isEmpty());
        // 第一个结果应为"罗德岛"页面（标题匹配得分最高）
        ToolResult.Item first = result.items().get(0);
        assertEquals("罗德岛", first.title());
        assertTrue(first.path().endsWith(".txt"));
        assertTrue(first.snippet().contains("罗德岛"));
        assertTrue(first.score() > 0);
    }

    @Test
    @DisplayName("应返回最多 max_results 条结果")
    void testMaxResults() {
        ToolCall call = new ToolCall("wiki_search",
                Map.of("keyword", "罗德岛", "max_results", "1"));
        ToolResult result = tool.execute(call);

        assertTrue(result.success());
        assertTrue(result.items().size() <= 1);
    }

    @Test
    @DisplayName("无匹配时应返回空结果提示")
    void testNoResults() {
        ToolCall call = new ToolCall("wiki_search", Map.of("keyword", "不存在的内容xyz"));
        ToolResult result = tool.execute(call);

        assertTrue(result.success());
        assertEquals(1, result.items().size());
        assertTrue(result.items().get(0).title().contains("无结果"));
    }

    @Test
    @DisplayName("应通过 ToolRegistry 按名称调用工具")
    void testRegistryCall() {
        ToolCall call = new ToolCall("wiki_search", Map.of("keyword", "源石"));
        ToolResult result = registry.call(call);

        assertTrue(result.success());
        assertFalse(result.items().isEmpty());
    }

    @Test
    @DisplayName("调用不存在的工具应返回失败")
    void testUnknownTool() {
        ToolCall call = new ToolCall("nonexistent", Map.of());
        ToolResult result = registry.call(call);

        assertFalse(result.success());
        assertTrue(result.error().contains("Unknown tool"));
    }

    @Test
    @DisplayName("snippet 长度应控制在合理范围内")
    void testSnippetLength() {
        ToolCall call = new ToolCall("wiki_search", Map.of("keyword", "罗德岛"));
        ToolResult result = tool.execute(call);

        assertTrue(result.success());
        for (ToolResult.Item item : result.items()) {
            assertTrue(item.snippet().length() <= 350,
                    "Snippet too long: " + item.snippet().length() + " chars");
        }
    }
}
