package com.gsim.agent.tools.search;

import static org.junit.jupiter.api.Assertions.*;

import com.gsim.agentlib.tool.ToolCall;
import com.gsim.agentlib.tool.ToolResult;
import com.gsim.core.search.SearchEntry;
import com.gsim.core.worldinfo.NodeSnapshot;
import com.gsim.core.worldinfo.WorldInformation;
import com.gsim.core.worldinfo.loader.WorldIndexManager;
import com.gsim.map.map.MapData;
import com.gsim.map.map.MapStore;
import com.gsim.map.service.MapService;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * GsimapSearchHexTool — {@code gsimap_search_hex} 单格域搜索测试。
 *
 * <p>覆盖：地形名命中（key=gsimap:hex:q_r）、description/symbol 文本命中、
 * 未注册地形回退原值、无匹配返回空、nodeId 无地图返回空（不抛异常）、
 * 语料规模守卫分支（超限时仅非空 description/symbol 的格被索引且不抛异常）、
 * wiSupplier 非空时的回合数推导路径。
 */
@DisplayName("GsimapSearchHexTool — 单格域搜索")
class GsimapSearchHexToolTest {

    private static final String WORLD = "mapworld";
    private static final String NO_MAP_WORLD = "nomapworld";

    private static final String KEY_ALTAR = "gsimap:hex:10_-5";
    private static final String KEY_FOREST = "gsimap:hex:0_0";
    private static final String KEY_WATER = "gsimap:hex:2_3";
    private static final String KEY_PLAINS = "gsimap:hex:-4_1";
    private static final String KEY_LAVA = "gsimap:hex:5_5";

    @TempDir
    Path tmpDir;

    private MapService mapService;
    private GsimapSearchHexTool tool;

    @BeforeEach
    void setUp() {
        WorldIndexManager.createWorld(tmpDir, WORLD, "地图世界");
        WorldIndexManager.createWorld(tmpDir, NO_MAP_WORLD, "无地图世界");

        MapData mapData = new MapData(
                30,
                false,
                Map.of(
                        // 森林 + description + symbol（非空标注）
                        "10_-5", new MapData.HexCell("#228B22", "forest", "祭", null, "神秘祭坛", 0, Map.of(), Map.of()),
                        // 森林，无标注（守卫模式下应被排除）
                        "0_0", MapData.HexCell.of("#228B22", "forest"),
                        // 水，仅 symbol
                        "2_3", new MapData.HexCell("#3292D5", "water", "W", null, "", 0, Map.of(), Map.of()),
                        // 平原，无标注
                        "-4_1", MapData.HexCell.of("#6CC261", "plains"),
                        // 未注册地形 lava（无 terrainTypes 定义 → 回退原值）
                        "5_5", MapData.HexCell.of("#000000", "lava")),
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
        tool = new GsimapSearchHexTool(new SearchToolContext(() -> null, mapService, null, null));
    }

    private ToolResult search(String worldId, String keywords) {
        return tool.execute(new ToolCall("gsimap_search_hex", Map.of("worldId", worldId, "keywords", keywords)));
    }

    @Test
    @DisplayName("地形名命中：key=gsimap:hex:q_r")
    void terrainNameHits() {
        ToolResult result = search(WORLD, "森林");
        assertTrue(result.success(), "error: " + result.error());
        assertEquals(2, result.items().size(), "items: " + result.items());
        Set<String> keys = result.items().stream().map(ToolResult.Item::path).collect(Collectors.toSet());
        assertEquals(Set.of(KEY_ALTAR, KEY_FOREST), keys, "keys: " + keys);
        ToolResult.Item hit = result.items().get(0);
        assertTrue(hit.snippet().startsWith("type=hex | "), "snippet: " + hit.snippet());
        assertTrue(hit.score() > 0, "score: " + hit.score());
    }

    @Test
    @DisplayName("description 文本命中单格")
    void descriptionHits() {
        ToolResult result = search(WORLD, "祭坛");
        assertTrue(result.success(), "error: " + result.error());
        assertEquals(1, result.items().size(), "items: " + result.items());
        ToolResult.Item hit = result.items().get(0);
        assertEquals(KEY_ALTAR, hit.path());
        assertEquals(KEY_ALTAR, hit.title());
        assertTrue(hit.snippet().contains("神秘祭坛"), "snippet: " + hit.snippet());
        assertTrue(hit.score() > 0, "score: " + hit.score());
    }

    @Test
    @DisplayName("symbol 文本命中单格")
    void symbolHits() {
        ToolResult result = search(WORLD, "W");
        assertTrue(result.success(), "error: " + result.error());
        assertEquals(1, result.items().size(), "items: " + result.items());
        assertEquals(KEY_WATER, result.items().get(0).path());
    }

    @Test
    @DisplayName("未注册地形回退 terrain 原值可搜")
    void unknownTerrainFallsBackToRawId() {
        ToolResult result = search(WORLD, "lava");
        assertTrue(result.success(), "error: " + result.error());
        assertEquals(1, result.items().size(), "items: " + result.items());
        assertEquals(KEY_LAVA, result.items().get(0).path());
    }

    @Test
    @DisplayName("无匹配关键词返回空 items")
    void noMatchReturnsEmpty() {
        ToolResult result = search(WORLD, "不存在的格子");
        assertTrue(result.success(), "error: " + result.error());
        assertTrue(result.items().isEmpty(), "items: " + result.items());
    }

    @Test
    @DisplayName("nodeId 无地图返回空 items（不抛异常）")
    void noMapReturnsEmpty() {
        ToolResult result = search(NO_MAP_WORLD, "森林");
        assertTrue(result.success(), "error: " + result.error());
        assertTrue(result.items().isEmpty(), "items: " + result.items());
    }

    @Test
    @DisplayName("语料规模守卫：超限时仅非空 description/symbol 的格被索引，不抛异常")
    void guardLimitsCorpusToAnnotatedHexes() {
        // 4 格 > 注入阈值 3 → 守卫生效：仅 10_-5（desc+symbol）与 2_3（symbol）入选
        GsimapSearchHexTool guarded =
                new GsimapSearchHexTool(new SearchToolContext(() -> null, mapService, null, null), 3);

        List<SearchEntry> entries = guarded.buildEntries(WORLD, "n0000");
        assertEquals(2, entries.size(), "entries: " + entries);
        Set<String> keys = entries.stream().map(SearchEntry::key).collect(Collectors.toSet());
        assertEquals(Set.of(KEY_ALTAR, KEY_WATER), keys, "keys: " + keys);

        // 端到端：仅靠地形名（空标注平原格）不再命中，标注文本照常命中
        ToolResult byTerrain =
                guarded.execute(new ToolCall("gsimap_search_hex", Map.of("worldId", WORLD, "keywords", "平原")));
        assertTrue(byTerrain.success(), "error: " + byTerrain.error());
        assertTrue(byTerrain.items().isEmpty(), "items: " + byTerrain.items());

        ToolResult byDesc =
                guarded.execute(new ToolCall("gsimap_search_hex", Map.of("worldId", WORLD, "keywords", "祭坛")));
        assertTrue(byDesc.success(), "error: " + byDesc.error());
        assertEquals(1, byDesc.items().size(), "items: " + byDesc.items());
        assertEquals(KEY_ALTAR, byDesc.items().get(0).path());

        ToolResult bySymbol =
                guarded.execute(new ToolCall("gsimap_search_hex", Map.of("worldId", WORLD, "keywords", "W")));
        assertTrue(bySymbol.success(), "error: " + bySymbol.error());
        assertEquals(1, bySymbol.items().size(), "items: " + bySymbol.items());
        assertEquals(KEY_WATER, bySymbol.items().get(0).path());
    }

    @Test
    @DisplayName("wiSupplier 非空时按分支链回合数推导 sortKey，搜索不受影响")
    void turnDerivationWithWorldInformation() {
        WorldInformation wi = new WorldInformation(
                WORLD, List.of(new NodeSnapshot("n0000", null, 3, "203年", "active", "t0", Map.of(), Map.of())));
        GsimapSearchHexTool wiTool = new GsimapSearchHexTool(new SearchToolContext(() -> wi, mapService, null, null));

        ToolResult result =
                wiTool.execute(new ToolCall("gsimap_search_hex", Map.of("worldId", WORLD, "keywords", "祭坛")));
        assertTrue(result.success(), "error: " + result.error());
        assertEquals(1, result.items().size(), "items: " + result.items());
        assertEquals(KEY_ALTAR, result.items().get(0).path());
    }
}
