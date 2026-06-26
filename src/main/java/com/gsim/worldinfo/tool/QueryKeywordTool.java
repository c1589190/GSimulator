package com.gsim.worldinfo.tool;

import com.gsim.tool.AgentTool;
import com.gsim.tool.ToolCall;
import com.gsim.tool.ToolResult;
import com.gsim.worldinfo.KeywordIndex;
import com.gsim.worldinfo.WorldInformation;

import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * query_keyword — full-text search across all elements in the world.
 */
public final class QueryKeywordTool implements AgentTool {

    private final Supplier<WorldInformation> worldInfo;

    public QueryKeywordTool(Supplier<WorldInformation> worldInfo) {
        this.worldInfo = worldInfo;
    }

    @Override
    public String name() { return "query_keyword"; }

    @Override
    public String description() {
        return "Full-text keyword search across all world information elements. " +
               "Returns matching elements with source attribution (nodeId, turn, checkpointId). " +
               "Supports pagination via offset.";
    }

    @Override
    public ToolResult execute(ToolCall call) {
        String keywords = call.param("keywords");
        if (keywords == null || keywords.isBlank()) {
            return ToolResult.fail("query_keyword", "keywords is required");
        }

        int limit = parseInt(call.param("limit"), 20);
        int offset = parseInt(call.param("offset"), 0);

        WorldInformation wi = worldInfo.get();
        KeywordIndex.SearchResult result = wi.keywordIndex().search(keywords, limit, offset);

        List<ToolResult.Item> items = result.items().stream()
            .map(hit -> new ToolResult.Item(
                hit.elementRef().element().key(),
                "%s@turn%d (%s)".formatted(hit.elementRef().nodeId(), hit.elementRef().turn(), hit.elementRef().checkpointId()),
                hit.snippet(),
                (double) hit.score()))
            .toList();

        return ToolResult.ok("query_keyword", items);
    }

    @Override
    public Map<String, Object> getParameters() {
        return Map.of(
            "type", "object",
            "properties", Map.of(
                "keywords", Map.of("type", "string", "description", "Space-separated search keywords"),
                "limit", Map.of("type", "integer", "description", "Max results (default 20)"),
                "offset", Map.of("type", "integer", "description", "Pagination offset (default 0)")
            ),
            "required", List.of("keywords")
        );
    }

    private static int parseInt(String s, int defaultVal) {
        if (s == null || s.isBlank()) return defaultVal;
        try { return Integer.parseInt(s); } catch (NumberFormatException e) { return defaultVal; }
    }
}
