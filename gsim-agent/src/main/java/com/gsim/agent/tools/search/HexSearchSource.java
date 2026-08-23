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
 * 单格（地块）域共享语料源（gsimap_search_hex 与 gsim_search 聚合器共用）。
 *
 * <p>语料语义（从 {@link GsimapSearchHexTool} 抽取，行为完全一致）：当前节点地图的
 * 全部 hex 各生成一条 {@link SearchEntry}，文本为
 * {@code terrain名称 + " " + description + " " + symbol + " " + tags}，key 为
 * {@code gsimap:hex:{q}_{r}}（q_r 即 hexes map 的键），sortKey 为该节点回合数。
 * tags 以 {@code key：value}（全角冒号 U+FF1A）逐对空格连接——分词器
 * {@code SearchTextTokenizer} 以全角冒号为分隔符，key 与 value 独立成段可搜。
 *
 * <p><b>规模守卫</b>：网格最大可达 1000×1000（全量 100 万格），当 {@code hexes}
 * 数量超过 {@value #DEFAULT_CORPUS_LIMIT} 时，只收录 description / symbol / tags
 * 任一非空的格（地形名单独不能作为入选理由，否则巨图下语料依然接近全量）。
 *
 * <p>包可见：仅限 search 包内工具/聚合器直接调用，避免 ToolRegistry 往返解析。
 */
final class HexSearchSource {

    /** 语料规模守卫上限：hexes 超过该数量时仅索引 description/symbol/tags 非空的格。 */
    static final int DEFAULT_CORPUS_LIMIT = 50_000;

    private final SearchToolContext ctx;
    private final int corpusLimit;

    HexSearchSource(SearchToolContext ctx) {
        this(ctx, DEFAULT_CORPUS_LIMIT);
    }

    /**
     * 包可见构造：注入语料上限，供测试以极小阈值覆盖守卫分支。
     *
     * @param ctx         搜索工具上下文
     * @param corpusLimit 语料守卫阈值，{@code hexes.size() > corpusLimit} 时启用守卫
     */
    HexSearchSource(SearchToolContext ctx, int corpusLimit) {
        this.ctx = ctx;
        this.corpusLimit = corpusLimit;
    }

    /**
     * 构建 hex 搜索语料。
     *
     * @param worldId         世界 ID
     * @param effectiveNodeId 生效节点 ID（null/空白 → 地图活跃节点）
     * @return 搜索语料列表（无地图或空图时为空）
     */
    List<SearchEntry> build(String worldId, String effectiveNodeId) {
        String nodeId = effectiveNodeId;
        if (nodeId == null || nodeId.isBlank()) {
            nodeId = ctx.mapService().readActiveNodeId(worldId);
        }
        MapData map = ctx.mapService().resolve(worldId, nodeId);
        if (map == null || map.hexes().isEmpty()) return List.of();
        boolean overLimit = map.hexes().size() > corpusLimit;
        long turn = turnOfNode(nodeId);
        List<SearchEntry> entries = new ArrayList<>();
        for (Map.Entry<String, MapData.HexCell> entry : map.hexes().entrySet()) {
            MapData.HexCell cell = entry.getValue();
            if (overLimit && !hasSearchableText(cell)) continue;
            String text = terrainName(map, cell) + " " + cell.description() + " " + symbolOrEmpty(cell) + " "
                    + tagsText(cell);
            entries.add(new SearchEntry(text, "gsimap:hex:" + entry.getKey(), turn));
        }
        return List.copyOf(entries);
    }

    /**
     * 地形显示名：优先取 {@code terrainTypes().get(terrain).name()}；地形类型缺失
     * （未注册/未知 terrain id）时回退使用 terrain 原值。
     */
    private static String terrainName(MapData map, MapData.HexCell cell) {
        MapData.TerrainType type = map.terrainTypes().get(cell.terrain());
        return (type != null && type.name() != null && !type.name().isBlank()) ? type.name() : cell.terrain();
    }

    /** symbol 可为 null（HexCell 构造器不归一化 symbol/symbolColor），置空避免 "null" 入语料。 */
    private static String symbolOrEmpty(MapData.HexCell cell) {
        return cell.symbol() != null ? cell.symbol() : "";
    }

    /**
     * 标签文本：每对 tags 渲染为 {@code key + "：" + value}（全角冒号 U+FF1A——分词器
     * 以其为分隔符，key/value 独立成段可搜），多标签以空格连接；tags 为空返回空串。
     */
    private static String tagsText(MapData.HexCell cell) {
        if (cell.tags().isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, String> tag : cell.tags().entrySet()) {
            if (!sb.isEmpty()) sb.append(' ');
            sb.append(tag.getKey()).append('：').append(tag.getValue());
        }
        return sb.toString();
    }

    /** 守卫模式下 description / symbol / tags 任一非空的格入选语料。 */
    private static boolean hasSearchableText(MapData.HexCell cell) {
        return (cell.description() != null && !cell.description().isBlank())
                || (cell.symbol() != null && !cell.symbol().isBlank())
                || !cell.tags().isEmpty();
    }

    /**
     * Hex 所在节点的回合数，作为条目排序键。
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
