package com.gsim.agent.tools.search;

import static org.junit.jupiter.api.Assertions.*;

import com.gsim.agent.tools.map.GsimapResolver;
import com.gsim.agentsmanager.mcp.GsimRequestContext;
import com.gsim.agentsmanager.tool.ToolCall;
import com.gsim.agentsmanager.tool.ToolResult;
import com.gsim.docslib.doc.DocStore;
import com.gsim.docslib.doc.DocType;
import com.gsim.core.ref.ResolverRegistry;
import com.gsim.core.worldinfo.Checkpoint;
import com.gsim.core.worldinfo.Element;
import com.gsim.core.worldinfo.NodeSnapshot;
import com.gsim.core.worldinfo.WorldInformation;
import com.gsim.core.worldinfo.loader.WorldIndexManager;
import com.gsim.map.map.MapData;
import com.gsim.map.map.MapStore;
import com.gsim.map.service.MapService;
import java.io.IOException;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * GsimSearchTool（{@code gsim_search} 聚合搜索工具）测试。
 *
 * <p>固定世界「searchworld」四域语料均可被关键词「迷雾」命中：
 * <ul>
 *   <li><b>region</b> — 区域 迷雾森林（name+tag+desc）</li>
 *   <li><b>hex</b> — 单格 0_0（terrain 森林 + description 迷雾笼罩的密林）；另有标签格
 *     2_0（tags 煤矿=储量300万吨，验证聚合器透传 hex 标签语料）</li>
 *   <li><b>world</b> — 元素 都城=长安迷雾（n0000:worldview:都城）</li>
 *   <li><b>doc</b> — 文档 mcp_search_doc（title 迷雾森林考察，直读 DocStore）</li>
 * </ul>
 * 域优先级固定 region → hex → world → doc。
 */
@DisplayName("GsimSearchTool — gsim_search 聚合搜索")
class GsimSearchToolTest {

    private static final String WORLD = "searchworld";
    private static final String REGION_KEY = "gsimap:region:迷雾森林";
    private static final String HEX_KEY = "gsimap:hex:0_0";
    private static final String WORLD_KEY = "n0000:worldview:都城";
    private static final String DOC_KEY = "@doc:mcp_search_doc";

    @TempDir
    Path tmpDir;

    private MapService mapService;
    private DocStore store;
    private GsimSearchTool tool;

    @AfterEach
    void clearRequestContext() {
        GsimRequestContext.clear();
    }

    @BeforeEach
    void setUp() throws IOException {
        WorldIndexManager.createWorld(tmpDir, WORLD, "搜索世界");

        MapData mapData = new MapData(
                30,
                false,
                Map.of(
                        // 森林 + 迷雾描述：与 region/world/doc 同词命中
                        "0_0",
                        new MapData.HexCell("#228B22", "forest", null, null, "迷雾笼罩的密林", 0, Map.of(), Map.of()),
                        // 无标注平原：不含「迷雾」相关字，不参与命中
                        "1_0",
                        MapData.HexCell.of("#6CC261", "plains"),
                        // 标签格：tags 文本经 HexSearchSource 透传进 hex 域语料
                        "2_0",
                        new MapData.HexCell(
                                "#808080", "mountain", null, null, "", 0, Map.of(), Map.of("煤矿", "储量300万吨"))),
                List.of(),
                Map.of(),
                Map.of(),
                List.of(),
                List.of(),
                MapData.TerrainType.defaults(),
                List.of(),
                Map.of(),
                Map.of());
        MapStore.saveFull(tmpDir, WORLD, "n0000", mapData);

        mapService = new MapService(tmpDir);
        assertOk(mapService.createRegion(WORLD, "n0000", "迷雾森林", "forest", "#228B22", "浓雾笼罩，常年不见天日", List.of("0_0")));
        assertOk(mapService.createRegion(WORLD, "n0000", "北境雪原", "雪原", "#EEEEEE", "终年积雪", List.of()));

        store = new DocStore(tmpDir.resolve("docs"));
        store.init();
        store.create("mcp_search_doc", DocType.OTHER, "迷雾森林考察", "浓雾与流水声交织的密林深处。", List.of("地理"));

        tool = new GsimSearchTool(new SearchToolContext(() -> newWorld(), mapService, store, null));
    }

    private static WorldInformation newWorld() {
        NodeSnapshot n0 = new NodeSnapshot(
                "n0000",
                null,
                0,
                "origin",
                "initial",
                "t0",
                Map.of(
                        "worldview",
                        new Checkpoint(
                                "世界观",
                                "worldview",
                                List.of(
                                        new Element("都城", "text", "长安迷雾", List.of("都城", "地理"), List.of(), null, null),
                                        new Element("史书", "text", "长安古都", List.of("史书"), List.of(), null, null)))),
                new LinkedHashMap<>());
        return new WorldInformation(WORLD, List.of(n0));
    }

    private static void assertOk(Map<String, Object> result) {
        assertEquals(Boolean.TRUE, result.get("ok"), "expected ok=true, got: " + result);
    }

    private ToolResult search(String query, String... kvPairs) {
        Map<String, String> params = new LinkedHashMap<>();
        params.put("worldId", WORLD);
        params.put("query", query);
        for (int i = 0; i + 1 < kvPairs.length; i += 2) {
            params.put(kvPairs[i], kvPairs[i + 1]);
        }
        return tool.execute(new ToolCall("search", params));
    }

    // ── 关键词模式：跨域聚合 ──

    @Test
    @DisplayName("同词命中四域 → 按域优先级 region→hex→world→doc 聚合")
    void keywordAggregatesAcrossDomainsInPriorityOrder() {
        ToolResult r = search("迷雾");

        assertTrue(r.success(), "error: " + r.error());
        List<String> paths = r.items().stream().map(ToolResult.Item::path).toList();
        assertIterableEquals(List.of(REGION_KEY, HEX_KEY, WORLD_KEY, DOC_KEY), paths, "paths: " + paths);

        // 每项带 type 标签（world 域沿用细化工具 domain=element）
        assertTrue(
                r.items().get(0).snippet().startsWith("type=region | "),
                r.items().get(0).snippet());
        assertTrue(
                r.items().get(1).snippet().startsWith("type=hex | "),
                r.items().get(1).snippet());
        assertTrue(
                r.items().get(2).snippet().startsWith("type=element | "),
                r.items().get(2).snippet());
        assertTrue(
                r.items().get(3).snippet().startsWith("type=doc | "),
                r.items().get(3).snippet());
        assertTrue(r.items().get(0).score() > 0, "score: " + r.items().get(0).score());
    }

    @Test
    @DisplayName("无匹配关键词返回空 items")
    void noMatchReturnsEmptyItems() {
        ToolResult r = search("虚无");
        assertTrue(r.success(), "error: " + r.error());
        assertTrue(r.items().isEmpty(), "items: " + r.items());
    }

    @Test
    @DisplayName("标签关键词命中 hex 域：聚合器透传 HexSearchSource，零改动仍工作")
    void keywordMatchesHexTagsThroughAggregator() {
        ToolResult byKey = search("煤矿");
        assertTrue(byKey.success(), "error: " + byKey.error());
        List<String> paths = byKey.items().stream().map(ToolResult.Item::path).toList();
        assertIterableEquals(List.of("gsimap:hex:2_0"), paths, "paths: " + paths);
        ToolResult.Item hit = byKey.items().get(0);
        assertTrue(hit.snippet().startsWith("type=hex | "), "snippet: " + hit.snippet());
        assertTrue(hit.snippet().contains("煤矿：储量300万吨"), "snippet: " + hit.snippet());
        assertTrue(hit.score() > 0, "score: " + hit.score());

        ToolResult byValue = search("300");
        assertTrue(byValue.success(), "error: " + byValue.error());
        assertIterableEquals(
                List.of("gsimap:hex:2_0"),
                byValue.items().stream().map(ToolResult.Item::path).toList());
    }

    // ── domains 过滤 ──

    @Test
    @DisplayName("domains 过滤：仅搜索指定域，域优先级序不变")
    void domainsFilterRestrictsDomains() {
        ToolResult r = search("迷雾", "domains", "world,hex");
        assertTrue(r.success(), "error: " + r.error());
        List<String> paths = r.items().stream().map(ToolResult.Item::path).toList();
        assertIterableEquals(List.of(HEX_KEY, WORLD_KEY), paths, "paths: " + paths);

        ToolResult docOnly = search("迷雾", "domains", "doc");
        assertTrue(docOnly.success());
        assertIterableEquals(
                List.of(DOC_KEY),
                docOnly.items().stream().map(ToolResult.Item::path).toList());

        ToolResult regionOnly = search("迷雾", "domains", "region");
        assertTrue(regionOnly.success());
        assertIterableEquals(
                List.of(REGION_KEY),
                regionOnly.items().stream().map(ToolResult.Item::path).toList());
    }

    @Test
    @DisplayName("domains 含未知值 → 忽略该域，其余域照常")
    void unknownDomainValueIsIgnored() {
        ToolResult r = search("迷雾", "domains", "bogus,region");
        assertTrue(r.success(), "error: " + r.error());
        assertIterableEquals(
                List.of(REGION_KEY),
                r.items().stream().map(ToolResult.Item::path).toList());
    }

    @Test
    @DisplayName("domains 全为未知值 → 空 items（显式过滤被尊重）")
    void allUnknownDomainsYieldEmpty() {
        ToolResult r = search("迷雾", "domains", "bogus");
        assertTrue(r.success(), "error: " + r.error());
        assertTrue(r.items().isEmpty(), "items: " + r.items());
    }

    // ── limit / offset ──

    @Test
    @DisplayName("limit/offset 作用于合并后的全局列表")
    void limitAndOffsetApplyToMergedList() {
        // 「长安」仅命中 world 域两元素（都城/史书，同分 → 输入序）
        ToolResult page1 = search("长安", "limit", "1", "offset", "0");
        assertTrue(page1.success());
        assertIterableEquals(
                List.of("n0000:worldview:都城"),
                page1.items().stream().map(ToolResult.Item::path).toList());

        ToolResult page2 = search("长安", "limit", "1", "offset", "1");
        assertTrue(page2.success());
        assertIterableEquals(
                List.of("n0000:worldview:史书"),
                page2.items().stream().map(ToolResult.Item::path).toList());

        // 全局 offset 越界 → 空页
        ToolResult past = search("长安", "offset", "5");
        assertTrue(past.success());
        assertTrue(past.items().isEmpty(), "items: " + past.items());
    }

    // ── 地址模式 ──

    @Test
    @DisplayName("地址模式：gsimap:region:迷雾森林 → 单条 resolved 结果，不走搜索")
    void addressModeResolvesGsimapRegionDirectly() {
        ResolverRegistry registry = ResolverRegistry.createWithBuiltins();
        registry.register(new GsimapResolver(mapService));
        GsimSearchTool addrTool = new GsimSearchTool(
                new SearchToolContext(() -> newWorld(), mapService, store, registry, tmpDir, null, null));

        ToolResult r =
                addrTool.execute(new ToolCall("search", Map.of("worldId", WORLD, "query", "gsimap:region:迷雾森林")));

        assertTrue(r.success(), "error: " + r.error());
        assertEquals(1, r.items().size(), "items: " + r.items());
        ToolResult.Item hit = r.items().get(0);
        assertEquals("gsimap:region:迷雾森林", hit.path());
        assertEquals("gsimap:region:迷雾森林", hit.title());
        assertEquals(1.0, hit.score(), "score: " + hit.score());
        assertTrue(hit.snippet().startsWith("type=resolved | "), "snippet: " + hit.snippet());
        assertTrue(hit.snippet().contains("迷雾森林"), "snippet: " + hit.snippet());
    }

    @Test
    @DisplayName("地址模式：@doc: 引用 → 单条 resolved 文档结果")
    void addressModeResolvesDocRef() {
        ResolverRegistry registry = ResolverRegistry.createWithBuiltins();
        GsimSearchTool addrTool = new GsimSearchTool(
                new SearchToolContext(() -> newWorld(), mapService, store, registry, tmpDir, null, null));

        ToolResult r =
                addrTool.execute(new ToolCall("search", Map.of("worldId", WORLD, "query", "@doc:mcp_search_doc")));

        assertTrue(r.success(), "error: " + r.error());
        assertEquals(1, r.items().size(), "items: " + r.items());
        ToolResult.Item hit = r.items().get(0);
        assertEquals("@doc:mcp_search_doc", hit.path());
        assertEquals(1.0, hit.score());
        assertTrue(hit.snippet().startsWith("type=resolved | "), "snippet: " + hit.snippet());
        assertTrue(hit.snippet().contains("迷雾森林考察"), "snippet: " + hit.snippet());
    }

    @Test
    @DisplayName("地址模式解析失败（未知引用）→ 回退关键词模式")
    void addressModeFallsBackToKeywordOnUnknownRef() {
        // 注册表未注册 GsimapResolver → gsimap: 解析抛异常 → 关键词模式兜底
        ResolverRegistry registry = ResolverRegistry.createWithBuiltins();
        GsimSearchTool addrTool = new GsimSearchTool(
                new SearchToolContext(() -> newWorld(), mapService, store, registry, tmpDir, null, null));

        ToolResult r =
                addrTool.execute(new ToolCall("search", Map.of("worldId", WORLD, "query", "gsimap:region:迷雾森林")));

        assertTrue(r.success(), "error: " + r.error());
        assertFalse(r.items().isEmpty(), "回退搜索应有命中: " + r.items());
        assertTrue(
                r.items().stream().noneMatch(i -> i.snippet().startsWith("type=resolved | ")),
                "不应出现 resolved 结果: " + r.items());
        // 域优先级：region 命中排首位
        ToolResult.Item first = r.items().get(0);
        assertEquals("gsimap:region:迷雾森林", first.path());
        assertTrue(first.snippet().startsWith("type=region | "), "snippet: " + first.snippet());
    }

    // ── 失败与空用例 ──

    @Test
    @DisplayName("缺 query → fail")
    void missingQueryFails() {
        ToolResult r = tool.execute(new ToolCall("search", Map.of("worldId", WORLD)));
        assertFalse(r.success());
        assertTrue(r.error().contains("query is required"), "error: " + r.error());
    }

    @Test
    @DisplayName("空白 query → fail")
    void blankQueryFails() {
        ToolResult r = search("   ");
        assertFalse(r.success());
        assertTrue(r.error().contains("query is required"), "error: " + r.error());
    }
}
