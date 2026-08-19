package com.gsim.agent.tools.map;

import static org.junit.jupiter.api.Assertions.*;

import com.gsim.agentlib.tool.ToolCall;
import com.gsim.agentlib.tool.ToolResult;
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
 * GsimapQueryHexByTagsTool — {@code gsimap_query_hex_by_tags} 按标签查询 hex 测试。
 *
 * <p>覆盖：tagKey 过滤（命中/排除无 tags 与异键格）、valueContains / valueNotContains
 * 子串过滤、组合过滤、无匹配返回空、tagKey 缺省报错、offset/limit 分页、snippet 含完整
 * tags 详情。
 */
@DisplayName("GsimapQueryHexByTagsTool — 按标签查询 hex")
class GsimapQueryHexByTagsToolTest {

    private static final String WORLD = "tagqueryworld";

    @TempDir
    Path tmpDir;

    private GsimapQueryHexByTagsTool tool;

    @BeforeEach
    void setUp() {
        WorldIndexManager.createWorld(tmpDir, WORLD, "标签世界");
        MapData mapData = new MapData(
                30,
                false,
                Map.of(
                        // A：煤炭资源 23662吨
                        "0_0",
                        new MapData.HexCell("#228B22", "forest", null, null, "", 0, Map.of(), Map.of("煤炭资源", "23662吨")),
                        // B：煤炭资源 100吨
                        "1_0",
                        new MapData.HexCell("#6CC261", "plains", null, null, "", 0, Map.of(), Map.of("煤炭资源", "100吨")),
                        // C：异键标签（铁矿）
                        "2_0",
                        new MapData.HexCell("#3292D5", "water", null, null, "", 0, Map.of(), Map.of("铁矿", "5吨")),
                        // D：无 tags
                        "3_0",
                        MapData.HexCell.of("#8B0000", "lava")),
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

        tool = new GsimapQueryHexByTagsTool(new MapService(tmpDir));
    }

    private ToolResult query(Map<String, String> params) {
        return tool.execute(new ToolCall("gsimap_query_hex_by_tags", params));
    }

    private Set<String> keys(ToolResult result) {
        return result.items().stream().map(ToolResult.Item::path).collect(Collectors.toSet());
    }

    @Test
    @DisplayName("按 tagKey 过滤：命中含该键的格，排除异键格与无 tags 格")
    void filterByTagKey() {
        ToolResult result = query(Map.of("worldId", WORLD, "tagKey", "煤炭资源"));
        assertTrue(result.success(), "error: " + result.error());
        assertEquals(2, result.items().size(), "items: " + result.items());
        assertEquals(Set.of("gsimap:hex:0_0", "gsimap:hex:1_0"), keys(result), "keys: " + keys(result));
    }

    @Test
    @DisplayName("valueContains：仅保留标签值包含子串的格")
    void filterByValueContains() {
        ToolResult result = query(Map.of("worldId", WORLD, "tagKey", "煤炭资源", "valueContains", "23662"));
        assertTrue(result.success(), "error: " + result.error());
        assertEquals(1, result.items().size(), "items: " + result.items());
        assertEquals(Set.of("gsimap:hex:0_0"), keys(result), "keys: " + keys(result));
    }

    @Test
    @DisplayName("valueNotContains：排除标签值包含子串的格")
    void filterByValueNotContains() {
        ToolResult result = query(Map.of("worldId", WORLD, "tagKey", "煤炭资源", "valueNotContains", "100"));
        assertTrue(result.success(), "error: " + result.error());
        assertEquals(1, result.items().size(), "items: " + result.items());
        assertEquals(Set.of("gsimap:hex:0_0"), keys(result), "keys: " + keys(result));
    }

    @Test
    @DisplayName("组合过滤：valueContains + valueNotContains 同时生效")
    void combinedContainsAndNotContains() {
        ToolResult result =
                query(Map.of("worldId", WORLD, "tagKey", "煤炭资源", "valueContains", "吨", "valueNotContains", "100"));
        assertTrue(result.success(), "error: " + result.error());
        assertEquals(1, result.items().size(), "items: " + result.items());
        assertEquals(Set.of("gsimap:hex:0_0"), keys(result), "keys: " + keys(result));
    }

    @Test
    @DisplayName("tagKey 无匹配：返回空 items 且 success")
    void noMatchReturnsEmpty() {
        ToolResult result = query(Map.of("worldId", WORLD, "tagKey", "金矿"));
        assertTrue(result.success(), "error: " + result.error());
        assertTrue(result.items().isEmpty(), "items: " + result.items());
    }

    @Test
    @DisplayName("tagKey 缺省：fail 且错误信息含 tagKey")
    void missingTagKeyFails() {
        ToolResult result = query(Map.of("worldId", WORLD));
        assertFalse(result.success(), "items: " + result.items());
        assertTrue(result.error().contains("tagKey"), "error: " + result.error());
    }

    @Test
    @DisplayName("分页：limit/offset 生效且按 hex key 自然序")
    void pagination() {
        ToolResult page0 = query(Map.of("worldId", WORLD, "tagKey", "煤炭资源", "limit", "1", "offset", "0"));
        assertTrue(page0.success(), "error: " + page0.error());
        assertEquals(1, page0.items().size(), "items: " + page0.items());
        assertEquals("gsimap:hex:0_0", page0.items().get(0).path());

        ToolResult page1 = query(Map.of("worldId", WORLD, "tagKey", "煤炭资源", "limit", "1", "offset", "1"));
        assertTrue(page1.success(), "error: " + page1.error());
        assertEquals(1, page1.items().size(), "items: " + page1.items());
        assertEquals("gsimap:hex:1_0", page1.items().get(0).path());
    }

    @Test
    @DisplayName("snippet 为 JSON 且含完整 tags 与 hexKey")
    void snippetContainsFullTags() {
        ToolResult result = query(Map.of("worldId", WORLD, "tagKey", "煤炭资源"));
        assertTrue(result.success(), "error: " + result.error());
        ToolResult.Item hit = result.items().get(0);
        assertEquals("0_0", hit.title());
        assertEquals("gsimap:hex:0_0", hit.path());
        assertTrue(hit.snippet().contains("煤炭资源"), "snippet: " + hit.snippet());
        assertTrue(hit.snippet().contains("0_0"), "snippet: " + hit.snippet());
        assertTrue(hit.snippet().contains("23662吨"), "snippet: " + hit.snippet());
        assertTrue(hit.score() == 1.0, "score: " + hit.score());
    }
}
