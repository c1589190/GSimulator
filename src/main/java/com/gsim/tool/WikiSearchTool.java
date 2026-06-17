package com.gsim.tool;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;

/**
 * Wiki 搜索工具 — 在 import/web/prts.wiki/ 下的本地 txt 文件中搜索关键词。
 *
 * 搜索方式：关键词匹配（不依赖 ChromaDB / 向量检索）。
 */
public class WikiSearchTool implements AgentTool {

    private static final Logger log = LoggerFactory.getLogger(WikiSearchTool.class);

    public static final String NAME = "wiki_search";

    private final LocalFileSearchService searchService;

    public WikiSearchTool(LocalFileSearchService searchService) {
        this.searchService = searchService;
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public String description() {
        return "Search the local PRTS Wiki text files for keyword matches. " +
                "Returns matching page titles, file paths, and text snippets.";
    }

    @Override
    public ToolResult execute(ToolCall call) {
        String keyword = call.param("keyword", call.param("query", ""));
        if (keyword.isBlank()) {
            return ToolResult.fail(NAME, "keyword is required");
        }

        String maxResultsStr = call.param("max_results", "10");
        int maxResults;
        try {
            maxResults = Integer.parseInt(maxResultsStr);
            if (maxResults <= 0) maxResults = 10;
            if (maxResults > 50) maxResults = 50;
        } catch (NumberFormatException e) {
            maxResults = 10;
        }

        log.info("WikiSearchTool: keyword='{}', maxResults={}", keyword, maxResults);

        List<ToolResult.Item> items = searchService.search(keyword, maxResults);

        if (items.isEmpty()) {
            return ToolResult.ok(NAME, List.of(
                    new ToolResult.Item("(无结果)", "", "未找到匹配「" + keyword + "」的页面。", 0)));
        }

        return ToolResult.ok(NAME, items);
    }
}
