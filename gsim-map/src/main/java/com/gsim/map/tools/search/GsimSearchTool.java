package com.gsim.map.tools.search;

import com.gsim.agentsmanager.mcp.GsimRequestContext;
import com.gsim.agentsmanager.ref.RefResolver.ResolvedRef;
import com.gsim.agentsmanager.ref.ResolverContext;
import com.gsim.agentsmanager.ref.ResolverRegistry;
import com.gsim.agentsmanager.tool.AgentTool;
import com.gsim.agentsmanager.tool.ToolCall;
import com.gsim.agentsmanager.tool.ToolResult;
import com.gsim.core.search.GenericSearchEngine;
import com.gsim.core.search.SearchEntry;
import com.gsim.core.search.SearchHit;
import com.gsim.core.search.SearchOptions;
import com.gsim.core.tools.search.DocSearchSource;
import com.gsim.core.tools.search.WorldSearchSource;
import com.gsim.core.worldinfo.Checkpoint;
import com.gsim.core.worldinfo.Element;
import com.gsim.core.worldinfo.NodeSnapshot;
import com.gsim.map.service.MapService;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * gsim_search — 统一聚合搜索工具（五域：world / region / hex / doc + 地址解析）。
 *
 * <p><b>地址模式</b>：query 匹配地址形态（{@code @world:}/{@code @doc:}/{@code @cache:}/
 * {@code @import:}/{@code gsimap:} 前缀，或 {@code nodeId:cpId:key} / {@code cpId:key}
 * 裸引用）时，经 {@link ResolverRegistry#resolve} 直接解析内容并返回单条结果
 * （type=resolved, score=1.0）——<em>不落到全文搜索</em>。解析抛异常（未知引用）时
 * 回退关键词模式。
 *
 * <p><b>关键词模式</b>：对每个启用域（{@code domains} 参数过滤，默认全部）复用
 * T4-T7 抽取的共享语料源（{@link RegionSearchSource}/{@link HexSearchSource}/
 * {@link WorldSearchSource}/{@link DocSearchSource}），域内 RELEVANCE 排序，按固定域
 * 优先级 region → hex → world → doc 拼接（同域保持引擎序），对合并列表施加全局
 * limit/offset，每个命中带 {@code type} 标签（world 域沿用细化工具 domain=element）。
 *
 * <p><b>不做跨域分数归一化</b>：域内分数 + 域优先级即可（计划 T8 明令）。
 */
public final class GsimSearchTool implements AgentTool {

    /** 地址形态 1：显式前缀（@world:/@doc:/@cache:/@import:/gsimap:）。 */
    private static final Pattern ADDR_PREFIX = Pattern.compile("^(@world:|@doc:|@cache:|@import:|gsimap:)");

    /** 地址形态 2：nodeId:cpId:key（nodeId 形如 {@code n0000}）。 */
    private static final Pattern ADDR_NODE_REF = Pattern.compile("^[a-z]+\\d{4}:[^:]+:.+$");

    /** 地址形态 3：cpId:key（两段裸引用）。 */
    private static final Pattern ADDR_CP_REF = Pattern.compile("^[^:\\s]+:[^:\\s]+$");

    /** 全部域（默认启用）。 */
    private static final Set<String> ALL_DOMAINS = Set.of("world", "region", "hex", "doc");

    /** 固定域优先级（region → hex → world → doc）：地图实体最具体，排最前。 */
    private static final List<String> DOMAIN_PRIORITY = List.of("region", "hex", "world", "doc");

    /** 地址模式 snippet 中正文预览的最大字符数。 */
    private static final int SNIPPET_CONTENT_MAX = 200;

    private final WorldSearchSource worldSource;
    private final RegionSearchSource regionSource;
    private final HexSearchSource hexSource;
    private final DocSearchSource docSource;

    /** 聚合器同时需要 core 版（world/doc 域）与 map 版（region/hex 域）上下文。 */
    private final com.gsim.core.tools.search.SearchToolContext ctx;

    public GsimSearchTool(com.gsim.core.tools.search.SearchToolContext ctx, MapService mapService) {
        this.ctx = ctx;
        this.worldSource = new WorldSearchSource(ctx);
        this.regionSource = new RegionSearchSource(ctx, mapService);
        this.hexSource = new HexSearchSource(ctx, mapService);
        this.docSource = new DocSearchSource(ctx);
    }

    @Override
    public String name() {
        // 短注册名（MCP wire 名 = gsim_search）：ToolRegistryMcpAdapter.toRegistryName 会无条件剥离
        // gsim_ 前缀后再查注册表，长名（gsim_search）会导致 guard 查找失败（UNKNOWN_TOOL）。
        return "search";
    }

    @Override
    public String description() {
        return "Unified search across world elements, map regions, map hexes and documents. "
                + "If the query looks like an address (@world:, @doc:, @cache:, @import:, gsimap:, "
                + "nodeId:cpId:key or cpId:key) it is resolved directly as a single hit (type=resolved). "
                + "Otherwise each enabled domain is searched by keyword and results are merged in fixed "
                + "domain priority order region -> hex -> world -> doc, tagged with the domain type. "
                + "Optional domains (comma list of world,region,hex,doc, default all); unknown values ignored; "
                + "supports pagination via limit/offset.";
    }

    @Override
    public ToolResult execute(ToolCall call) {
        String query = call.param("query");
        if (query == null || query.isBlank()) {
            return ToolResult.fail(name(), "query is required");
        }
        query = query.trim();

        String worldId = GsimRequestContext.worldId();
        if (worldId == null || worldId.isBlank()) {
            worldId = call.param("worldId");
        }

        String effectiveNodeId = call.param("nodeId");
        if (effectiveNodeId == null || effectiveNodeId.isBlank()) {
            effectiveNodeId = null;
        }

        int limit = parseInt(call.param("limit"), 20);
        int offset = parseInt(call.param("offset"), 0);
        List<String> domains = parseDomains(call.param("domains"));

        // ── 地址模式：直接解析，不落到全文搜索 ──
        if (isAddressForm(query)) {
            ToolResult resolved = tryResolveAddress(query, worldId);
            if (resolved != null) return resolved;
        }

        // ── 关键词模式：各域独立搜索，按固定域优先级合并 ──
        // 每域取 limit+offset 条即足够（全局窗口 [offset, offset+limit) 只可能取到
        // 合并列表前 offset+limit 条内的命中）。
        int largeLimit = limit + offset;
        List<ToolResult.Item> merged = new ArrayList<>();
        for (String domain : DOMAIN_PRIORITY) {
            if (!domains.contains(domain)) continue;
            List<SearchEntry> entries = buildCorpus(domain, worldId, effectiveNodeId);
            List<ToolResult.Item> hits = searchDomain(entries, domain, query, largeLimit);
            merged.addAll(hits);
        }

        int from = Math.max(0, Math.min(offset, merged.size()));
        // limit<=0 → 空页（与引擎 SearchOptions 语义一致）；offset/limit 为负时钳制避免越界
        int to = Math.max(from, Math.min(from + Math.max(limit, 0), merged.size()));
        return ToolResult.ok(name(), List.copyOf(merged.subList(from, to)));
    }

    /**
     * 按域构建语料（复用共享语料源；world/doc 域的过滤参数在聚合器语义下不适用，
     * 一律不过滤）。
     */
    private List<SearchEntry> buildCorpus(String domain, String worldId, String effectiveNodeId) {
        return switch (domain) {
            case "region" -> regionSource.build(worldId, effectiveNodeId);
            case "hex" -> hexSource.build(worldId, effectiveNodeId);
            case "world" -> worldSource.build(worldId, effectiveNodeId, null);
            case "doc" -> docSource.build(worldId, effectiveNodeId, null, null);
            default -> List.of();
        };
    }

    /** 单域搜索：RELEVANCE 排序，命中映射为带 {@code type} 标签的条目。 */
    private List<ToolResult.Item> searchDomain(List<SearchEntry> entries, String domain, String query, int largeLimit) {
        String type = domain.equals("world") ? "element" : domain;
        List<SearchHit> hits = GenericSearchEngine.search(
                entries, query, new SearchOptions(largeLimit, 0, SearchOptions.SortMode.RELEVANCE));
        return hits.stream()
                .map(hit ->
                        new ToolResult.Item(hit.key(), hit.key(), "type=" + type + " | " + hit.snippet(), hit.score()))
                .toList();
    }

    // ── 地址模式 ──

    /**
     * 尝试以地址模式解析；解析失败（未知引用 / 上下文缺失）返回 null 触发关键词回退。
     *
     * <p>worldsDir 为 null（MapSearchToolContext 以兼容四参构造）时无法构造
     * {@link ResolverContext}（其构造器强制非空 worldsDir），视为不可解析直接回退。
     */
    private ToolResult tryResolveAddress(String query, String worldId) {
        ResolverRegistry registry = ctx.registry();
        if (registry == null || ctx.worldsDir() == null) return null;
        try {
            ResolverContext rctx =
                    ResolverContext.of(ctx.worldsDir(), worldId, ctx.importDir(), ctx.docStore(), ctx.cacheDir());
            ResolvedRef ref = registry.resolve(query, rctx);
            // 世界元素地址：应用 scope gate（含 internal 硬规则）。
            // 其他源（doc/cache/import/gsimap）不适用元素级 tag 门控。
            if ("world".equals(ref.source())) {
                com.gsim.agentsmanager.QueryScope scope = com.gsim.agentsmanager.QueryScopeContext.get();
                if (scope == null) scope = com.gsim.agentsmanager.QueryScope.none();
                if (!isWorldRefAllowed(scope, ref.id())) {
                    return ToolResult.fail(name(), "地址 '" + query + "' 不在当前 Agent 的查询权限范围内");
                }
            }
            String snippet = ref.title() != null ? ref.title() : "";
            if (ref.content() != null && !ref.content().isBlank()) {
                String preview = ref.content().length() > SNIPPET_CONTENT_MAX
                        ? ref.content().substring(0, SNIPPET_CONTENT_MAX) + "…"
                        : ref.content();
                snippet = snippet.isBlank() ? preview : snippet + " | " + preview;
            }
            return ToolResult.ok(name(), List.of(new ToolResult.Item(query, query, "type=resolved | " + snippet, 1.0)));
        } catch (RuntimeException e) {
            // 未知引用（IllegalArgumentException）/上下文缺失（IllegalStateException/NPE）→ 关键词模式兜底
            return null;
        }
    }

    /**
     * 世界元素地址（{@code ref.id()} 形如 {@code nodeId:cpId:key}）的 scope 判定。
     *
     * <p>地址模式经 {@link ResolverRegistry} 解析返回的 {@link ResolvedRef}
     * 只含 content，不含元素 {@code tags}。要应用 internal 硬规则与 tag 白名单，
     * 需回溯到源元素取 tags。解析失败时保守放行（保持地址模式既有行为）。
     */
    private boolean isWorldRefAllowed(com.gsim.agentsmanager.QueryScope scope, String worldRefId) {
        String[] parts = worldRefId.split(":", 3);
        if (parts.length != 3) return true;
        String nodeId = parts[0], cpId = parts[1], key = parts[2];
        var wi = ctx.wiSupplier() != null ? ctx.wiSupplier().get() : null;
        if (wi == null) return true;
        NodeSnapshot node = wi.nodeById(nodeId);
        if (node == null) return true;
        Checkpoint cp = node.checkpoint(cpId);
        if (cp == null) return true;
        for (Element el : cp.elements()) {
            if (key.equals(el.key())) {
                return scope.allows(nodeId, cpId, key, el.tags());
            }
        }
        return true;
    }

    /**
     * 判定 query 是否为地址形态：
     * <ol>
     *   <li>{@code @world:}/{@code @doc:}/{@code @cache:}/{@code @import:}/{@code gsimap:} 前缀</li>
     *   <li>{@code nodeId:cpId:key}（nodeId 形如 {@code n0000}）</li>
     *   <li>{@code cpId:key} 两段裸引用</li>
     * </ol>
     */
    private static boolean isAddressForm(String query) {
        return ADDR_PREFIX.matcher(query).find()
                || ADDR_NODE_REF.matcher(query).matches()
                || ADDR_CP_REF.matcher(query).matches();
    }

    // ── 参数解析 ──

    /**
     * 解析 domains 参数：逗号分隔，未知值忽略；参数缺失/空白 → 全部域；
     * 显式给出但全部未知 → 空列表（尊重显式过滤）。
     */
    private static List<String> parseDomains(String domainsParam) {
        if (domainsParam == null || domainsParam.isBlank()) return List.copyOf(DOMAIN_PRIORITY);
        Set<String> result = new LinkedHashSet<>();
        for (String raw : domainsParam.split(",")) {
            String domain = raw.trim().toLowerCase(Locale.ROOT);
            if (ALL_DOMAINS.contains(domain)) result.add(domain);
        }
        return List.copyOf(result);
    }

    private static int parseInt(String s, int defaultVal) {
        if (s == null || s.isBlank()) return defaultVal;
        try {
            return Integer.parseInt(s);
        } catch (NumberFormatException e) {
            return defaultVal;
        }
    }

    // ── AgentTool 协议 ──

    @Override
    public Map<String, Object> getParameters() {
        return Map.of(
                "type", "object",
                "properties",
                        Map.of(
                                "query",
                                        Map.of(
                                                "type",
                                                "string",
                                                "description",
                                                "Search keywords or a gsim address (@world:, @doc:, @cache:, @import:, gsimap:, nodeId:cpId:key, cpId:key)"),
                                "nodeId",
                                        Map.of(
                                                "type",
                                                "string",
                                                "description",
                                                "Target node id (default: active node)"),
                                "domains",
                                        Map.of(
                                                "type",
                                                "string",
                                                "description",
                                                "Comma list of domains to search: world,region,hex,doc (default all)"),
                                "limit", Map.of("type", "integer", "description", "Max results (default 20)"),
                                "offset", Map.of("type", "integer", "description", "Pagination offset (default 0)")),
                "required", List.of("query"));
    }

    @Override
    public boolean requiresWorldId() {
        return true;
    }

    @Override
    public AgentTool.Permission permission() {
        return AgentTool.Permission.READ;
    }
}
