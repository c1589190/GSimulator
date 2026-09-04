package com.gsim.map.tools.search;

import static org.junit.jupiter.api.Assertions.*;

import com.gsim.agentsmanager.tool.ToolCall;
import com.gsim.agentsmanager.tool.ToolResult;
import com.gsim.core.tools.search.SearchToolContext;
import com.gsim.core.worldinfo.NodeSnapshot;
import com.gsim.core.worldinfo.WorldInformation;
import com.gsim.core.worldinfo.loader.WorldIndexManager;
import com.gsim.map.map.MapData;
import com.gsim.map.map.MapStore;
import com.gsim.map.service.MapService;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * GsimapSearchRegionTool — {@code gsimap_search_region} 区域域搜索测试。
 *
 * <p>覆盖：区域名命中（key=gsimap:region:{name}）、tag/description 文本命中、
 * 无匹配返回空、nodeId 无地图返回空（不抛异常）、wiSupplier 非空时的回合数
 * 推导路径。
 */
@DisplayName("GsimapSearchRegionTool — 区域域搜索")
class GsimapSearchRegionToolTest {

    private static final String WORLD = "mapworld";
    private static final String NO_MAP_WORLD = "nomapworld";

    @TempDir
    Path tmpDir;

    private MapService mapService;
    private GsimapSearchRegionTool tool;

    @BeforeEach
    void setUp() {
        WorldIndexManager.createWorld(tmpDir, WORLD, "地图世界");
        WorldIndexManager.createWorld(tmpDir, NO_MAP_WORLD, "无地图世界");

        MapData mapData = new MapData(
                30,
                false,
                Map.of(MapData.hexKey(0, 0), MapData.HexCell.of("#6CC261", "plains")),
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
        assertOk(mapService.createRegion(
                WORLD, "n0000", "迷雾森林", "forest", "#228B22", "浓雾笼罩，常年不见天日", List.of(MapData.hexKey(0, 0))));
        // 北境雪原 的 tag/描述保持纯 CJK：T1 分词器对 ASCII 查询按字母 unigram 拆分，
        // "forest" 的 f/o/r/e/s/t 会与 "snow" 的 s/o 碰撞（见 tagHits），故不可用 ASCII tag。
        assertOk(mapService.createRegion(WORLD, "n0000", "北境雪原", "雪原", "#EEEEEE", "终年积雪", List.of()));

        tool = new GsimapSearchRegionTool(new SearchToolContext(() -> null, null, null), mapService);
    }

    private static void assertOk(Map<String, Object> result) {
        assertEquals(Boolean.TRUE, result.get("ok"), "expected ok=true, got: " + result);
    }

    private ToolResult search(String worldId, String keywords) {
        return tool.execute(new ToolCall("gsimap_search_region", Map.of("worldId", worldId, "keywords", keywords)));
    }

    @Test
    @DisplayName("区域名命中：key=gsimap:region:{name}")
    void regionNameHits() {
        ToolResult result = search(WORLD, "迷雾");
        assertTrue(result.success(), "error: " + result.error());
        assertEquals(1, result.items().size(), "items: " + result.items());
        ToolResult.Item hit = result.items().get(0);
        assertEquals("gsimap:region:迷雾森林", hit.path());
        assertEquals("gsimap:region:迷雾森林", hit.title());
        assertTrue(hit.snippet().startsWith("type=region | "), "snippet: " + hit.snippet());
        assertTrue(hit.snippet().contains("迷雾森林"), "snippet: " + hit.snippet());
        assertTrue(hit.score() > 0, "score: " + hit.score());
    }

    @Test
    @DisplayName("tag 文本命中区域：结果集恰为预期区域")
    void tagHits() {
        // T1 分词器把 "forest" 拆为 [forest, f, o, r, e, s, t]（整段 + 逐字母 unigram）。
        // 北境雪原 文本为纯 CJK（北境雪原 雪原 终年积雪），不含任何 ASCII 字母 → 零碰撞。
        ToolResult result = search(WORLD, "forest");
        assertTrue(result.success(), "error: " + result.error());
        List<String> paths = result.items().stream().map(ToolResult.Item::path).toList();
        assertIterableEquals(List.of("gsimap:region:迷雾森林"), paths);
    }

    @Test
    @DisplayName("description 文本命中区域")
    void descriptionHits() {
        ToolResult result = search(WORLD, "积雪");
        assertTrue(result.success(), "error: " + result.error());
        assertEquals(1, result.items().size(), "items: " + result.items());
        assertEquals("gsimap:region:北境雪原", result.items().get(0).path());
        assertTrue(
                result.items().get(0).snippet().contains("终年积雪"),
                "snippet: " + result.items().get(0).snippet());
    }

    @Test
    @DisplayName("无匹配关键词返回空 items")
    void noMatchReturnsEmpty() {
        // "虚无之地" 的 unigram（虚/无/之/地）与全部语料零重叠——迷雾森林（迷雾森林 forest
        // 浓雾笼罩，常年不见天日）与 北境雪原（北境雪原 雪原 终年积雪）均不含这些字。
        // 注意不可用含 "不" 的查询："不" 会命中 迷雾森林 描述中的 "不见天日"。
        ToolResult result = search(WORLD, "虚无之地");
        assertTrue(result.success(), "error: " + result.error());
        assertTrue(result.items().isEmpty(), "items: " + result.items());
    }

    @Test
    @DisplayName("nodeId 无地图返回空 items（不抛异常）")
    void noMapReturnsEmpty() {
        ToolResult result = search(NO_MAP_WORLD, "迷雾");
        assertTrue(result.success(), "error: " + result.error());
        assertTrue(result.items().isEmpty(), "items: " + result.items());
    }

    @Test
    @DisplayName("wiSupplier 非空时按分支链回合数推导 sortKey，搜索不受影响")
    void turnDerivationWithWorldInformation() {
        WorldInformation wi = new WorldInformation(
                WORLD, List.of(new NodeSnapshot("n0000", null, 3, "203年", "active", "t0", Map.of(), Map.of())));
        GsimapSearchRegionTool wiTool =
                new GsimapSearchRegionTool(new SearchToolContext(() -> wi, null, null), mapService);

        ToolResult result =
                wiTool.execute(new ToolCall("gsimap_search_region", Map.of("worldId", WORLD, "keywords", "迷雾")));
        assertTrue(result.success(), "error: " + result.error());
        assertEquals(1, result.items().size(), "items: " + result.items());
        assertEquals("gsimap:region:迷雾森林", result.items().get(0).path());
    }
}
