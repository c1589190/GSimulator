package com.gsim.core.tools.search;

import com.gsim.agentsmanager.mcp.GsimRequestContext;
import com.gsim.agentsmanager.tool.AgentTool;
import com.gsim.agentsmanager.tool.ToolCall;
import com.gsim.agentsmanager.tool.ToolResult;
import com.gsim.core.search.GenericSearchEngine;
import com.gsim.core.search.SearchEntry;
import com.gsim.core.search.SearchHit;
import com.gsim.core.search.SearchOptions;
import java.util.List;
import java.util.Map;

/**
 * 领域搜索工具骨架 — 共享的通用搜索模板方法。
 *
 * <p>子类只需实现三个抽象方法：
 * <ul>
 *   <li>{@link #buildEntries(String, String)} — 返回本域搜索语料（{@link SearchEntry} 列表）</li>
 *   <li>{@link #defaultNodeId(String)} — 未显式指定 nodeId 时的域内活跃节点默认值</li>
 *   <li>{@link #domain()} — 域标识（element / region / hex / doc），拼入结果 snippet</li>
 * </ul>
 *
 * <p>{@link #execute(ToolCall)} 统一处理参数解析（keywords 必填、nodeId 默认活跃节点、
 * limit/offset 分页）、worldId 解析（优先 {@link GsimRequestContext#worldId()}，
 * 回退到 {@code call.param("worldId")}）、调用 {@link GenericSearchEngine#search} 并
 * 将命中映射为 {@code ToolResult.Item(title=key, path=key, snippet="type=<domain> | ...", score)}。
 *
 * <p>子类可通过覆写 {@link #buildEntries(String, String, ToolCall)} 在语料构造阶段
 * 读取调用参数做额外过滤（如 gsim_search_world 的 checkpointId）。
 */
public abstract class AbstractSearchTool implements AgentTool {

    /** 共享依赖上下文（各域按需取用，未使用的组件可为 null）。 */
    protected final SearchToolContext ctx;

    protected AbstractSearchTool(SearchToolContext ctx) {
        this.ctx = ctx;
    }

    @Override
    public ToolResult execute(ToolCall call) {
        String keywords = call.param("keywords");
        if (keywords == null || keywords.isBlank()) {
            return ToolResult.fail(name(), "keywords is required");
        }

        String worldId = GsimRequestContext.worldId();
        if (worldId == null || worldId.isBlank()) {
            worldId = call.param("worldId");
        }

        String effectiveNodeId = call.param("nodeId");
        if (effectiveNodeId == null || effectiveNodeId.isBlank()) {
            effectiveNodeId = defaultNodeId(worldId);
        }

        int limit = parseInt(call.param("limit"), 20);
        int offset = parseInt(call.param("offset"), 0);

        List<SearchEntry> entries = buildEntries(worldId, effectiveNodeId, call);
        List<SearchHit> hits = GenericSearchEngine.search(
                entries, keywords, new SearchOptions(limit, offset, SearchOptions.SortMode.RELEVANCE));

        List<ToolResult.Item> items = toItems(hits, worldId, effectiveNodeId);

        return ToolResult.ok(name(), items);
    }

    /**
     * 将搜索命中映射为工具结果条目（可覆写以增强 snippet）。
     *
     * <p>默认实现保持历史行为：title=key、path=key、
     * {@code snippet = "type=<domain> | <hit.snippet>"}、score 透传。子类可覆写
     * 此方法为命中附加域特定详情（如 gsimap_search_hex 追加 terrain/description/tags）。
     *
     * @param hits            搜索命中列表
     * @param worldId         世界 ID（未提供且无上下文时可能为 null）
     * @param effectiveNodeId 生效节点 ID（已解析默认值的 nodeId）
     * @return 工具结果条目列表
     */
    protected List<ToolResult.Item> toItems(List<SearchHit> hits, String worldId, String effectiveNodeId) {
        return hits.stream()
                .map(hit -> new ToolResult.Item(
                        hit.key(), hit.key(), "type=" + domain() + " | " + hit.snippet(), hit.score()))
                .toList();
    }

    /**
     * 构建搜索语料（可被覆写以按调用参数过滤）。
     *
     * <p>默认委托给两参 {@link #buildEntries(String, String)}；子类可覆写此方法读取
     * {@code call.param(...)} 中的域特定过滤参数（如 checkpointId）。
     *
     * @param worldId        世界 ID
     * @param effectiveNodeId 生效节点 ID（已解析默认值的 nodeId）
     * @param call           原始工具调用（可读取额外过滤参数）
     * @return 搜索语料列表
     */
    protected List<SearchEntry> buildEntries(String worldId, String effectiveNodeId, ToolCall call) {
        return buildEntries(worldId, effectiveNodeId);
    }

    /**
     * 返回本域搜索语料。
     *
     * <p>实现者返回领域语料（世界元素 / 区域 / 六角格 / 文档等），每个条目
     * {@code text} 为搜索目标全文、{@code key} 为定位标识、{@code sortKey} 为稳定排序键。
     *
     * @param worldId         世界 ID
     * @param effectiveNodeId 生效节点 ID（已解析默认值的 nodeId）
     * @return 搜索语料列表
     */
    protected abstract List<SearchEntry> buildEntries(String worldId, String effectiveNodeId);

    /**
     * 未显式指定 nodeId 时的域内默认节点 ID（通常为活跃节点）。
     *
     * @param worldId 世界 ID
     * @return 默认节点 ID
     */
    protected abstract String defaultNodeId(String worldId);

    /**
     * 域标识，拼入结果 snippet（如 {@code "type=element | ..."}）。
     *
     * @return 域标识字符串
     */
    protected abstract String domain();

    @Override
    public boolean requiresWorldId() {
        return true;
    }

    @Override
    public AgentTool.Permission permission() {
        return AgentTool.Permission.READ;
    }

    @Override
    public Map<String, Object> getParameters() {
        return Map.of(
                "type", "object",
                "properties",
                        Map.of(
                                "keywords", Map.of("type", "string", "description", "Space-separated search keywords"),
                                "nodeId",
                                        Map.of(
                                                "type",
                                                "string",
                                                "description",
                                                "Target node id (default: active node)"),
                                "limit", Map.of("type", "integer", "description", "Max results (default 20)"),
                                "offset", Map.of("type", "integer", "description", "Pagination offset (default 0)")),
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
}
