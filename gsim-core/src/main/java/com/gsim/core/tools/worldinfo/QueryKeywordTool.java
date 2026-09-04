package com.gsim.core.tools.worldinfo;

import com.gsim.agentsmanager.tool.AgentTool;
import com.gsim.agentsmanager.tool.AgentTool.Permission;
import com.gsim.agentsmanager.tool.ToolCall;
import com.gsim.agentsmanager.tool.ToolResult;
import com.gsim.core.worldinfo.KeywordIndex;
import com.gsim.core.worldinfo.WorldInformation;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * query_keyword -- 在世界所有元素中执行全文关键词搜索。
 *
 * <p>使用 {@link com.gsim.core.worldinfo.KeywordIndex} 进行全文检索，
 * 返回匹配的元素摘要及来源信息（节点 ID、检查点 ID、回合号）。
 * 支持分页（limit/offset）和按检查点过滤（checkpointId 精确匹配）。
 *
 * <p>每个匹配结果包含来源引用 {@code nodeId:checkpointId:key} 和相关性评分。
 */
public final class QueryKeywordTool implements AgentTool {

    private final Supplier<WorldInformation> worldInfo;

    public QueryKeywordTool(Supplier<WorldInformation> worldInfo) {
        this.worldInfo = worldInfo;
    }

    @Override
    public String name() {
        return "query_keyword";
    }

    @Override
    public String description() {
        return "Full-text keyword search across all world information elements. "
                + "Returns matching elements with source attribution (nodeId, turn, checkpointId). "
                + "Supports pagination via offset. "
                + "Optional checkpointId filter to limit search to a specific checkpoint.";
    }

    @Override
    public ToolResult execute(ToolCall call) {
        String keywords = call.param("keywords");
        if (keywords == null || keywords.isBlank()) {
            return ToolResult.fail("query_keyword", "keywords is required");
        }

        int limit = parseInt(call.param("limit"), 20);
        int offset = parseInt(call.param("offset"), 0);
        String checkpointId = call.param("checkpointId");

        WorldInformation wi = worldInfo.get();
        KeywordIndex.SearchResult result = wi.keywordIndex().search(keywords, limit, offset, checkpointId);

        java.util.List<KeywordIndex.SearchHit> hits = result.items();
        com.gsim.agentsmanager.QueryScope scope = com.gsim.agentsmanager.QueryScopeContext.get();
        if (scope == null) scope = com.gsim.agentsmanager.QueryScope.none();
        final com.gsim.agentsmanager.QueryScope finalScope = scope;
        hits = hits.stream().filter(h -> finalScope.allows(h.elementRef())).toList();

        List<ToolResult.Item> items = hits.stream()
                .map(hit -> new ToolResult.Item(
                        hit.elementRef().element().key(),
                        hit.elementRef().nodeId() + ":" + hit.elementRef().checkpointId() + ":"
                                + hit.elementRef().element().key(),
                        hit.snippet(),
                        (double) hit.score()))
                .toList();

        return ToolResult.ok("query_keyword", items);
    }

    @Override
    public Map<String, Object> getParameters() {
        return Map.of(
                "type", "object",
                "properties",
                        Map.of(
                                "keywords", Map.of("type", "string", "description", "Space-separated search keywords"),
                                "limit", Map.of("type", "integer", "description", "Max results (default 20)"),
                                "offset", Map.of("type", "integer", "description", "Pagination offset (default 0)"),
                                "checkpointId",
                                        Map.of(
                                                "type",
                                                "string",
                                                "description",
                                                "Optional checkpoint ID to filter results (exact match)")),
                "required", List.of("keywords"));
    }

    private static int parseInt(String s, int defaultVal) {
        if (s == null || s.isBlank()) return defaultVal;
        try {
            return Integer.parseInt(s);
        } catch (NumberFormatException e) {
            return defaultVal;
        }
    }

    @Override
    public boolean requiresWorldId() {
        return true;
    }

    @Override
    public Permission permission() {
        return Permission.READ;
    }
}
