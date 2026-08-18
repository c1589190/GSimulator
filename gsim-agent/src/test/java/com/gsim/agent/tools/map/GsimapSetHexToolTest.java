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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * GsimapSetHexTool — {@code gsimap_set_hex} 写工具测试。
 *
 * <p>覆盖：happy（写 description+tags → ToolResult.ok 且结果含新 tags）、
 * 缺 nodeId → fail("nodeId is required")、hex 不存在 → fail。
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
    }
}
