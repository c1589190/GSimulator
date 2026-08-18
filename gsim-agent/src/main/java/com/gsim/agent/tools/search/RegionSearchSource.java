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
 * 区域（省/领地）域共享语料源（gsimap_search_region 与 gsim_search 聚合器共用）。
 *
 * <p>语料语义（从 {@link GsimapSearchRegionTool} 抽取，行为完全一致）：当前节点地图的
 * 全部区域（{@link MapData#provinces()}）各生成一条 {@link SearchEntry}，文本为
 * {@code name + " " + tag + " " + description}（Province 无 name 字段，名称即
 * provinces map 的键），key 为 {@code gsimap:region:{name}}，sortKey 为该节点回合数
 * （世界信息不可用时回退 0，只影响并列排序）。
 *
 * <p>包可见：仅限 search 包内工具/聚合器直接调用，避免 ToolRegistry 往返解析。
 */
final class RegionSearchSource {

    private final SearchToolContext ctx;

    RegionSearchSource(SearchToolContext ctx) {
        this.ctx = ctx;
    }

    /**
     * 构建区域搜索语料。
     *
     * @param worldId         世界 ID
     * @param effectiveNodeId 生效节点 ID（null/空白 → 地图活跃节点）
     * @return 搜索语料列表（无地图时为空）
     */
    List<SearchEntry> build(String worldId, String effectiveNodeId) {
        String nodeId = effectiveNodeId;
        if (nodeId == null || nodeId.isBlank()) {
            nodeId = ctx.mapService().readActiveNodeId(worldId);
        }
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
