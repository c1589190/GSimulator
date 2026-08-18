package com.gsim.agent.tools.search;

import com.gsim.core.search.SearchEntry;
import com.gsim.core.worldinfo.NodeSnapshot;
import com.gsim.core.worldinfo.WorldInformation;
import com.gsim.map.map.MapData;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * gsimap_search_region — 区域（省/领地）域全文搜索工具。
 *
 * <p>将当前节点地图中的全部区域（{@link MapData#provinces()}）构造成搜索语料，
 * 通过 {@code GenericSearchEngine} 做关键词全文检索。Province 记录没有独立
 * name 字段，名称即 {@code provinces} map 的键（见 {@code MapData.Province}）。
 *
 * <p>每条区域的语料为 {@code name + " " + tag + " " + description}，命中返回
 * {@code key = "gsimap:region:{name}"}、type=region。区域所在节点的回合数
 * （取自 {@link WorldInformation} 的 branchChain）作为排序键，世界信息不可用
 * 时回退 0（只影响并列排序，不影响命中）。
 *
 * <p>这是「区域名称在世界搜索中不可见」原 bug 的直接验证工具。
 */
public final class GsimapSearchRegionTool extends AbstractSearchTool {

    public GsimapSearchRegionTool(SearchToolContext ctx) {
        super(ctx);
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
        return ctx.mapService().readActiveNodeId(worldId);
    }

    @Override
    protected List<SearchEntry> buildEntries(String worldId, String nodeId) {
        MapData map = ctx.mapService().resolve(worldId, nodeId);
        if (map == null) return List.of();
        long turn = turnOfNode(nodeId);
        List<SearchEntry> entries = new ArrayList<>(map.provinces().size());
        for (Map.Entry<String, MapData.Province> entry : map.provinces().entrySet()) {
            String name = entry.getKey();
            MapData.Province prov = entry.getValue();
            String text = name + " " + prov.tag() + " " + prov.description();
            entries.add(new SearchEntry(text, "gsimap:region:" + name, turn));
        }
        return List.copyOf(entries);
    }

    /**
     * 区域所在节点的回合数，作为条目排序键。
     *
     * <p>世界信息（wiSupplier）为 null、解析结果为 null、或节点不在分支链上时
     * 一律回退 0 —— 此时条目仍然可搜，只是并列排序退化为输入序。
     */
    private long turnOfNode(String nodeId) {
        Supplier<WorldInformation> supplier = ctx.wiSupplier();
        if (supplier == null) return 0;
        WorldInformation wi = supplier.get();
        if (wi == null) return 0;
        NodeSnapshot node = wi.nodeById(nodeId);
        return node != null ? node.turn() : 0;
    }
}
