package com.gsim.agent.tools.search;

import com.gsim.agentlib.tool.ToolCall;
import com.gsim.core.search.SearchEntry;
import java.util.List;
import java.util.Map;

/**
 * gsim_search_world — 世界元素域细化搜索工具。
 *
 * <p>将分支链（根 → 活跃节点）上所有检查点的全部元素（值 + 标签）作为搜索语料，
 * 使用共享的 {@link com.gsim.core.search.GenericSearchEngine} 做全文检索。
 * 每个命中条目 key 为 {@code nodeId:checkpointId:elementKey}，sortKey 为节点回合号。
 * 语料构造委托给共享的 {@link WorldSearchSource}（与 gsim_search 聚合器共用）。
 *
 * <p>参数：
 * <ul>
 *   <li>{@code keywords}（必填）— 搜索关键词</li>
 *   <li>{@code nodeId}（可选，默认活跃节点）— 指定节点时语料限定为该节点所在分支
 *       （根 → nodeId，即该节点的世界状态）</li>
 *   <li>{@code checkpointId}（可选）— 仅搜索指定检查点的元素</li>
 *   <li>{@code limit} / {@code offset}（可选）— 分页</li>
 * </ul>
 */
public final class GsimSearchWorldTool extends AbstractSearchTool {

    private final WorldSearchSource source;

    public GsimSearchWorldTool(SearchToolContext ctx) {
        super(ctx);
        this.source = new WorldSearchSource(ctx);
    }

    @Override
    public String name() {
        // 短注册名（MCP wire 名 = gsim_search_world）：ToolRegistryMcpAdapter.toRegistryName
        // 会无条件剥离 gsim_ 前缀后再查注册表，长名会导致 MCP guard 查找失败。
        return "search_world";
    }

    @Override
    public String description() {
        return "Full-text search across all world information elements (values and tags). "
                + "Returns matching elements as nodeId:checkpointId:key references with relevance scores. "
                + "Optional nodeId scopes the search to that node's branch; optional checkpointId "
                + "restricts the search to a single checkpoint; supports pagination via limit/offset.";
    }

    @Override
    public Map<String, Object> getParameters() {
        Map<String, Object> base = super.getParameters();
        @SuppressWarnings("unchecked")
        Map<String, Object> props = new java.util.LinkedHashMap<>((Map<String, Object>) base.get("properties"));
        props.put(
                "checkpointId",
                Map.of("type", "string", "description", "Optional checkpoint ID to filter results (exact match)"));
        return Map.of("type", "object", "properties", props, "required", List.of("keywords"));
    }

    @Override
    protected String domain() {
        return "element";
    }

    @Override
    protected String defaultNodeId(String worldId) {
        return ctx.wiSupplier().get().activeNodeId();
    }

    @Override
    protected List<SearchEntry> buildEntries(String worldId, String effectiveNodeId, ToolCall call) {
        return source.build(worldId, effectiveNodeId, call.param("checkpointId"));
    }

    @Override
    protected List<SearchEntry> buildEntries(String worldId, String effectiveNodeId) {
        return source.build(worldId, effectiveNodeId, null);
    }
}
