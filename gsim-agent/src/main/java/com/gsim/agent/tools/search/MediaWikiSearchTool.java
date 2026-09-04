package com.gsim.agent.tools.search;

import com.gsim.agentsmanager.tool.AgentTool;
import com.gsim.agentsmanager.tool.AgentTool.Permission;
import com.gsim.agentsmanager.tool.ToolCall;
import com.gsim.agentsmanager.tool.ToolResult;
import com.gsim.core.webimport.MediaWikiApiClient;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 通用 MediaWiki 搜索工具 — 搜索任意 MediaWiki API 端点。
 *
 * <p>默认使用 Wikipedia（https://en.wikipedia.org/w/api.php）。
 * 可通过 wiki_url 参数指定其他 MediaWiki 站点（如 PRTS wiki）。
 * 支持两步搜索：先搜索文章列表，再按需获取摘要。
 */
public class MediaWikiSearchTool implements AgentTool {

    private static final Logger log = LoggerFactory.getLogger(MediaWikiSearchTool.class);

    public static final String NAME = "mediawiki_search";

    public static final String DEFAULT_WIKI_URL = "https://en.wikipedia.org/w/api.php";
    public static final String DEFAULT_USER_AGENT = "GSimulator/1.0 (research tool)";

    private final String defaultWikiUrl;
    private final String defaultUserAgent;

    /** 默认使用 Wikipedia + 默认 User-Agent。 */
    public MediaWikiSearchTool() {
        this(DEFAULT_WIKI_URL, DEFAULT_USER_AGENT);
    }

    public MediaWikiSearchTool(String wikiUrl) {
        this(wikiUrl, DEFAULT_USER_AGENT);
    }

    public MediaWikiSearchTool(String wikiUrl, String userAgent) {
        this.defaultWikiUrl = wikiUrl;
        this.defaultUserAgent = userAgent;
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public String description() {
        return """
            Search any MediaWiki API for articles matching a query.
            Defaults to Wikipedia. Supports any MediaWiki site by changing wiki_url.
            Parameters:
            - query: search keywords
            - wiki_url (optional): MediaWiki API endpoint (default https://en.wikipedia.org/w/api.php)
            - limit (optional): max results 1-20 (default 10)
            - fetch_extracts (optional): 'true' to fetch article summaries (slower but richer)
            """;
    }

    @Override
    public ToolResult execute(ToolCall call) {
        String query = call.param("query", "");
        if (query.isBlank()) {
            return ToolResult.fail(NAME, "query is required");
        }

        String wikiUrl = call.param("wiki_url", defaultWikiUrl);
        int limit = parseInt(call.param("limit"), 10);
        boolean fetchExtracts = "true".equalsIgnoreCase(call.param("fetch_extracts"));

        // Normalize: ensure URL ends with api.php
        if (!wikiUrl.endsWith("api.php")) {
            if (wikiUrl.endsWith("/")) wikiUrl = wikiUrl.substring(0, wikiUrl.length() - 1);
            wikiUrl = wikiUrl + "/api.php";
        }

        log.info("MediaWikiSearchTool: query='{}' wiki={} limit={} extracts={}", query, wikiUrl, limit, fetchExtracts);

        MediaWikiApiClient client = new MediaWikiApiClient(wikiUrl, 15, defaultUserAgent);

        try {
            List<MediaWikiApiClient.SearchResult> results = client.search(query, limit);

            if (results.isEmpty()) {
                return ToolResult.ok(
                        NAME,
                        List.of(new ToolResult.Item(
                                "(无结果)", wikiUrl, "No results for '" + query + "' on " + wikiUrl, 0)));
            }

            List<ToolResult.Item> items = new ArrayList<>();
            for (var r : results) {
                String snippet = r.snippet();
                double score = Math.min(r.wordCount() / 100.0, 10.0);

                if (fetchExtracts) {
                    String extract = client.getExtract(r.pageId());
                    if (extract != null && !extract.isBlank()) {
                        snippet = extract;
                        score += 5.0;
                    }
                }

                items.add(new ToolResult.Item(r.title(), wikiUrl + "?pageid=" + r.pageId(), snippet, score));
            }

            return ToolResult.ok(NAME, items);

        } catch (Exception e) {
            log.error("MediaWikiSearchTool failed: {}", e.getMessage());
            return ToolResult.fail(NAME, "Search failed for '" + query + "' on " + wikiUrl + ": " + e.getMessage());
        }
    }

    @Override
    public Map<String, Object> getParameters() {
        return Map.of(
                "type", "object",
                "properties",
                        Map.of(
                                "query", Map.of("type", "string", "description", "Search keywords"),
                                "wiki_url",
                                        Map.of(
                                                "type",
                                                "string",
                                                "description",
                                                "MediaWiki API endpoint URL. Default: https://en.wikipedia.org/w/api.php. "
                                                        + "Examples: https://prts.wiki/api.php, https://zh.wikipedia.org/w/api.php"),
                                "limit", Map.of("type", "integer", "description", "Max results 1-20 (default 10)"),
                                "fetch_extracts",
                                        Map.of(
                                                "type",
                                                "string",
                                                "description",
                                                "Set to 'true' to fetch full article intros (slower but richer). Default: false")),
                "required", List.of("query"));
    }

    private static int parseInt(String s, int defaultVal) {
        if (s == null || s.isBlank()) return defaultVal;
        try {
            return Math.clamp(Integer.parseInt(s), 1, 20);
        } catch (NumberFormatException e) {
            return defaultVal;
        }
    }

    @Override
    public Permission permission() {
        return Permission.READ;
    }
}
