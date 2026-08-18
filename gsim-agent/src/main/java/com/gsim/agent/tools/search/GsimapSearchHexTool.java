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
 * gsimap_search_hex — 单格（地块）域全文搜索工具。
 *
 * <p>将当前节点地图中的全部 hex（{@link MapData#hexes()}）构造成搜索语料，
 * 通过 {@code GenericSearchEngine} 做关键词全文检索。每格语料为
 * {@code terrain名称 + " " + description + " " + symbol}，命中返回
 * {@code key = "gsimap:hex:{q}_{r}"}（{@code q_r} 即 hexes map 的键）、
 * type=hex。
 *
 * <p><b>规模守卫</b>：网格最大可达 1000×1000（全量 100 万格），为控制每请求
 * 语料构建成本，当 {@code hexes} 数量超过 {@value #DEFAULT_CORPUS_LIMIT}
 * （常量名 {@code DEFAULT_CORPUS_LIMIT}，可配置，默认 50_000）时，只收录
 * description 或 symbol 非空的格（地形名单独不能作为入选理由，否则巨图下语料
 * 依然接近全量）。
 *
 * <p>Hex 所在节点的回合数（取自 {@link WorldInformation} 的 branchChain）作为
 * 排序键，世界信息不可用时回退 0（只影响并列排序，不影响命中）。
 */
public final class GsimapSearchHexTool extends AbstractSearchTool {

    /** 语料规模守卫上限：hexes 超过该数量时仅索引 description/symbol 非空的格。 */
    public static final int DEFAULT_CORPUS_LIMIT = 50_000;

    private final int corpusLimit;

    public GsimapSearchHexTool(SearchToolContext ctx) {
        this(ctx, DEFAULT_CORPUS_LIMIT);
    }

    /**
     * 包可见构造：注入语料上限，供测试以极小阈值覆盖守卫分支
     * （生产代码使用 {@link #DEFAULT_CORPUS_LIMIT}）。
     *
     * @param ctx         搜索工具上下文
     * @param corpusLimit 语料守卫阈值，{@code hexes.size() > corpusLimit} 时启用守卫
     */
    GsimapSearchHexTool(SearchToolContext ctx, int corpusLimit) {
        super(ctx);
        this.corpusLimit = corpusLimit;
    }

    @Override
    public String name() {
        return "gsimap_search_hex";
    }

    @Override
    public String description() {
        return """
            Search hex cells on the current map by keyword.
            Matches terrain name, per-hex description and symbol text; returns hits
            keyed gsimap:hex:{q}_{r} with type=hex.
            Parameters: worldId (required), keywords (required), nodeId (optional),
            limit (optional, default 20), offset (optional, default 0).
            """;
    }

    @Override
    protected String domain() {
        return "hex";
    }

    @Override
    protected String defaultNodeId(String worldId) {
        return ctx.mapService().readActiveNodeId(worldId);
    }

    @Override
    protected List<SearchEntry> buildEntries(String worldId, String nodeId) {
        MapData map = ctx.mapService().resolve(worldId, nodeId);
        if (map == null || map.hexes().isEmpty()) return List.of();
        boolean overLimit = map.hexes().size() > corpusLimit;
        long turn = turnOfNode(nodeId);
        List<SearchEntry> entries = new ArrayList<>();
        for (Map.Entry<String, MapData.HexCell> entry : map.hexes().entrySet()) {
            MapData.HexCell cell = entry.getValue();
            if (overLimit && !hasSearchableText(cell)) continue;
            String text = terrainName(map, cell) + " " + cell.description() + " " + symbolOrEmpty(cell);
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

    /** 守卫模式下仅 description/symbol 非空的格入选语料。 */
    private static boolean hasSearchableText(MapData.HexCell cell) {
        return (cell.description() != null && !cell.description().isBlank())
                || (cell.symbol() != null && !cell.symbol().isBlank());
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
