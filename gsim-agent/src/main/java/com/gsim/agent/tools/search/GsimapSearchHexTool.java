package com.gsim.agent.tools.search;

import com.gsim.core.search.SearchEntry;
import java.util.List;

/**
 * gsimap_search_hex — 单格（地块）域全文搜索工具。
 *
 * <p>将当前节点地图中的全部 hex（{@link com.gsim.map.map.MapData#hexes()}）构造成
 * 搜索语料，通过 {@code GenericSearchEngine} 做关键词全文检索。每格语料为
 * {@code terrain名称 + " " + description + " " + symbol}，命中返回
 * {@code key = "gsimap:hex:{q}_{r}"}（{@code q_r} 即 hexes map 的键）、type=hex。
 * 语料构造委托给共享的 {@link HexSearchSource}（与 gsim_search 聚合器共用）。
 *
 * <p><b>规模守卫</b>：网格最大可达 1000×1000（全量 100 万格），为控制每请求
 * 语料构建成本，当 {@code hexes} 数量超过 {@value #DEFAULT_CORPUS_LIMIT}
 * （常量名 {@code DEFAULT_CORPUS_LIMIT}，可配置，默认 50_000）时，只收录
 * description 或 symbol 非空的格（地形名单独不能作为入选理由，否则巨图下语料
 * 依然接近全量）。
 *
 * <p>Hex 所在节点的回合数（取自 {@link com.gsim.core.worldinfo.WorldInformation}
 * 的 branchChain）作为排序键，世界信息不可用时回退 0（只影响并列排序，不影响命中）。
 */
public final class GsimapSearchHexTool extends AbstractSearchTool {

    /** 语料规模守卫上限：hexes 超过该数量时仅索引 description/symbol 非空的格。 */
    public static final int DEFAULT_CORPUS_LIMIT = HexSearchSource.DEFAULT_CORPUS_LIMIT;

    private final HexSearchSource source;

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
        this.source = new HexSearchSource(ctx, corpusLimit);
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
        return source.build(worldId, nodeId);
    }
}
