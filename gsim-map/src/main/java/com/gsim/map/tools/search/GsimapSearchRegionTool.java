package com.gsim.map.tools.search;

import com.gsim.core.search.SearchEntry;
import com.gsim.core.tools.search.AbstractSearchTool;
import java.util.List;

/**
 * gsimap_search_region — 区域（省/领地）域全文搜索工具。
 *
 * <p>将当前节点地图中的全部区域（{@link com.gsim.map.map.MapData#provinces()}）
 * 构造成搜索语料，通过 {@code GenericSearchEngine} 做关键词全文检索。Province 记录
 * 没有独立 name 字段，名称即 {@code provinces} map 的键（见
 * {@code MapData.Province}）。语料构造委托给共享的 {@link RegionSearchSource}
 * （与 gsim_search 聚合器共用）。
 *
 * <p>每条区域的语料为 {@code name + " " + tag + " " + description}，命中返回
 * {@code key = "gsimap:region:{name}"}、type=region。区域所在节点的回合数
 * （取自 {@link com.gsim.core.worldinfo.WorldInformation} 的 branchChain）作为排序键，
 * 世界信息不可用时回退 0（只影响并列排序，不影响命中）。
 *
 * <p>这是「区域名称在世界搜索中不可见」原 bug 的直接验证工具。
 */
public final class GsimapSearchRegionTool extends AbstractSearchTool {

    private final RegionSearchSource source;
    private final com.gsim.map.service.MapService mapService;

    public GsimapSearchRegionTool(
            com.gsim.core.tools.search.SearchToolContext ctx, com.gsim.map.service.MapService mapService) {
        super(ctx);
        this.mapService = mapService;
        this.source = new RegionSearchSource(ctx, mapService);
    }

    @Override
    public String name() {
        return "gsimap_search_region";
    }

    @Override
    public String description() {
        return """
            Search region (province) definitions on the current map by keyword.
            Matches region name, tag and description text; returns hits keyed
            gsimap:region:{name} with type=region.
            Parameters: worldId (required), keywords (required), nodeId (optional),
            limit (optional, default 20), offset (optional, default 0).
            """;
    }

    @Override
    protected String domain() {
        return "region";
    }

    @Override
    protected String defaultNodeId(String worldId) {
        return mapService.readActiveNodeId(worldId);
    }

    @Override
    protected List<SearchEntry> buildEntries(String worldId, String nodeId) {
        return source.build(worldId, nodeId);
    }
}
