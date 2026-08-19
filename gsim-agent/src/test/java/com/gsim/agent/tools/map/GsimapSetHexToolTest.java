package com.gsim.agent.tools.map;

import static org.junit.jupiter.api.Assertions.*;

import com.gsim.agentlib.tool.ToolCall;
import com.gsim.agentlib.tool.ToolResult;
import com.gsim.core.worldinfo.loader.WorldIndexManager;
import com.gsim.map.map.MapData;
import com.gsim.map.map.MapStore;
import com.gsim.map.service.MapService;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * GsimapSetHexTool — {@code gsimap_set_hex} 写工具测试。
 *
 * <p>覆盖：happy（写 description+tags → ToolResult.ok 且结果含新 tags）、
 * 缺 nodeId → fail("nodeId is required")、hex 不存在 → fail、
 * 批量模式（hexKeys → N 个 item / 合并语义 / description 覆盖 / 缺失静默跳过 / 全缺失 fail）。
 */
@DisplayName("GsimapSetHexTool — hex 描述/标签写工具")
class GsimapSetHexToolTest {

    private static final String WORLD = "sethexworld";

    @TempDir
    Path tmpDir;

    private MapService mapService;
    private GsimapSetHexTool tool;

    @BeforeEach
    void setUp() {
        WorldIndexManager.createWorld(tmpDir, WORLD, "写标签世界");
        Map<String, MapData.HexCell> cells = new LinkedHashMap<>();
        cells.put(MapData.hexKey(0, 0), MapData.HexCell.of("#6CC261", "plains"));
        cells.put(MapData.hexKey(1, 0), MapData.HexCell.of("#6CC261", "plains"));
        cells.put(MapData.hexKey(2, 0), MapData.HexCell.of("#6CC261", "plains"));
        MapData mapData = new MapData(
                30,
                false,
                cells,
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
        tool = new GsimapSetHexTool(mapService);
    }

    private ToolResult execute(Map<String, String> args) {
        return tool.execute(new ToolCall("gsimap_set_hex", args));
    }

    @Test
    @DisplayName("happy：写 description+tags → ok 且结果含新 tags")
    void setTagsSucceeds() {
        ToolResult result = execute(Map.of(
                "worldId", WORLD,
                "nodeId", "n0000",
                "q", "0",
                "r", "0",
                "description", "王都",
                "tags", "{\"图标显示\":\"⭐\",\"煤炭资源\":\"23662吨\"}"));
        assertTrue(result.success(), "should succeed: " + result.error());
        assertEquals(1, result.items().size());
        ToolResult.Item item = result.items().get(0);
        assertEquals("gsimap:hex:0_0", item.path(), "item path is the hex key");
        assertTrue(item.snippet().contains("图标显示=⭐"), "snippet should carry tags: " + item.snippet());
        assertTrue(item.snippet().contains("煤炭资源=23662吨"), "snippet should carry tags: " + item.snippet());
        assertTrue(item.snippet().contains("王都"), "snippet should carry description: " + item.snippet());
    }

    @Test
    @DisplayName("缺 nodeId → fail 含 nodeId is required")
    void missingNodeIdFails() {
        ToolResult result = execute(Map.of("worldId", WORLD, "q", "0", "r", "0"));
        assertFalse(result.success(), "should reject a call without nodeId");
        assertTrue(result.error().contains("nodeId is required"), "error: " + result.error());
    }

    @Test
    @DisplayName("hex 不存在 → fail")
    void hexNotFoundFails() {
        ToolResult result = execute(Map.of("worldId", WORLD, "nodeId", "n0000", "q", "99", "r", "-99"));
        assertFalse(result.success(), "should fail for a missing hex");
        assertEquals("Hex not found: 99_-99", result.error());
    }

    @Test
    @DisplayName("getParameters 声明 nodeId 必填")
    void nodeIdDeclaredRequired() {
        @SuppressWarnings("unchecked")
        Map<String, Object> params = tool.getParameters();
        @SuppressWarnings("unchecked")
        List<Object> required = (List<Object>) params.get("required");
        assertTrue(required.contains("nodeId"), "required=" + required);
        assertFalse(required.contains("q"), "q should no longer be required: " + required);
        assertFalse(required.contains("r"), "r should no longer be required: " + required);
        @SuppressWarnings("unchecked")
        Map<String, Object> props = (Map<String, Object>) params.get("properties");
        assertTrue(props.containsKey("hexKeys"), "hexKeys should be declared: " + props.keySet());
    }

    @Test
    @DisplayName("批量：hexKeys=0_0,1_0,2_0 + tags → 3 个 hex 都获得 tag，返回 3 个 item")
    void batchSetsTagsOnAllHexes() {
        ToolResult result = execute(Map.of(
                "worldId", WORLD,
                "nodeId", "n0000",
                "hexKeys", "0_0,1_0,2_0",
                "tags", "{\"图标显示\":\"⭐\"}"));
        assertTrue(result.success(), "should succeed: " + result.error());
        assertEquals(3, result.items().size(), "one item per hex key");
        for (ToolResult.Item item : result.items()) {
            assertTrue(item.path().startsWith("gsimap:hex:"), "path: " + item.path());
            assertTrue(item.snippet().contains("图标显示=⭐"), "snippet should carry tag: " + item.snippet());
        }
        assertEquals("0_0", result.items().get(0).title(), "items keep input order");
        assertEquals("1_0", result.items().get(1).title());
        assertEquals("2_0", result.items().get(2).title());
    }

    @Test
    @DisplayName("批量合并语义：已有 tags 未提及 key 保留")
    void batchMergesTagsWithoutRemovingExisting() {
        ToolResult first = execute(
                Map.of("worldId", WORLD, "nodeId", "n0000", "q", "0", "r", "0", "tags", "{\"煤炭资源\":\"23662吨\"}"));
        assertTrue(first.success(), "should set initial tag: " + first.error());

        ToolResult result = execute(Map.of(
                "worldId", WORLD,
                "nodeId", "n0000",
                "hexKeys", "0_0",
                "tags", "{\"铁矿\":\"5\"}"));
        assertTrue(result.success(), "should succeed: " + result.error());
        String snippet = result.items().get(0).snippet();
        assertTrue(snippet.contains("煤炭资源=23662吨"), "existing tag must be preserved: " + snippet);
        assertTrue(snippet.contains("铁矿=5"), "new tag must be present: " + snippet);
    }

    @Test
    @DisplayName("批量 description：提供则覆盖所有 hex；不提供则保持原样")
    void batchDescriptionOverwriteAndPreserve() {
        ToolResult overwrite = execute(Map.of(
                "worldId", WORLD,
                "nodeId", "n0000",
                "hexKeys", "0_0,1_0",
                "description", "王都"));
        assertTrue(overwrite.success(), "should succeed: " + overwrite.error());
        for (ToolResult.Item item : overwrite.items()) {
            assertTrue(item.snippet().contains("王都"), "description should be overwritten: " + item.snippet());
        }

        ToolResult keep =
                execute(Map.of("worldId", WORLD, "nodeId", "n0000", "hexKeys", "0_0", "tags", "{\"图标显示\":\"⭐\"}"));
        assertTrue(keep.success(), "should succeed: " + keep.error());
        String snippet = keep.items().get(0).snippet();
        assertTrue(snippet.contains("王都"), "description must be preserved when not provided: " + snippet);
    }

    @Test
    @DisplayName("批量含不存在 hex：存在的生效，缺失静默跳过，不抛异常")
    void batchWithMissingHexSucceeds() {
        ToolResult result = execute(Map.of(
                "worldId", WORLD,
                "nodeId", "n0000",
                "hexKeys", "0_0,99_99",
                "tags", "{\"图标显示\":\"⭐\"}"));
        assertTrue(result.success(), "should not fail on a partially missing batch: " + result.error());
        assertEquals(2, result.items().size(), "one item per input hex key");
        assertTrue(result.items().get(0).snippet().contains("图标显示=⭐"), "existing hex should be updated");
    }

    @Test
    @DisplayName("全部不存在 → fail 且 error 含 hex key")
    void batchAllMissingFails() {
        ToolResult result = execute(Map.of(
                "worldId", WORLD,
                "nodeId", "n0000",
                "hexKeys", "99_99,98_98",
                "tags", "{\"图标显示\":\"⭐\"}"));
        assertFalse(result.success(), "should fail when no hex exists");
        assertTrue(result.error().contains("99_99"), "error should list the hex key: " + result.error());
        assertTrue(result.error().contains("98_98"), "error should list all missing keys: " + result.error());
    }

    @Test
    @DisplayName("hexKeys 全空白/空集合 → fail hexKeys is empty")
    void batchCommaOnlyFails() {
        ToolResult result = execute(Map.of("worldId", WORLD, "nodeId", "n0000", "hexKeys", ","));
        assertFalse(result.success(), "should fail for an empty batch");
        assertTrue(result.error().contains("hexKeys is empty"), "error: " + result.error());
    }

    @Test
    @DisplayName("hexKeys 为空字符串 → 走单 hex 路径；单 hex 缺 q/r → fail 含 q/r or hexKeys")
    void emptyHexKeysFallsBackToSingleHex() {
        ToolResult result = execute(Map.of("worldId", WORLD, "nodeId", "n0000", "hexKeys", ""));
        assertFalse(result.success(), "should fail when q/r are missing");
        assertTrue(result.error().contains("q/r or hexKeys"), "error: " + result.error());
    }

    @Test
    @DisplayName("单 hex 回归：q=0 r=0 + tags → 行为与之前完全一致（1 个 item）")
    void singleHexRegression() {
        ToolResult result = execute(Map.of(
                "worldId", WORLD,
                "nodeId", "n0000",
                "q", "0",
                "r", "0",
                "tags", "{\"图标显示\":\"⭐\"}"));
        assertTrue(result.success(), "should succeed: " + result.error());
        assertEquals(1, result.items().size());
        ToolResult.Item item = result.items().get(0);
        assertEquals("0_0", item.title());
        assertEquals("gsimap:hex:0_0", item.path());
        assertTrue(item.snippet().contains("图标显示=⭐"), "snippet should carry tags: " + item.snippet());
    }
}
