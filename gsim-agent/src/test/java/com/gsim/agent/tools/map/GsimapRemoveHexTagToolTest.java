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
 * GsimapRemoveHexTagTool — {@code gsimap_remove_hex_tag} 写工具测试。
 *
 * <p>覆盖：happy 删单 key、tagKey 不存在 → fail、hex 不存在 → fail、缺 nodeId → fail。
 */
@DisplayName("GsimapRemoveHexTagTool — hex 标签删除工具")
class GsimapRemoveHexTagToolTest {

    private static final String WORLD = "removehextagworld";

    @TempDir
    Path tmpDir;

    private MapService mapService;
    private GsimapRemoveHexTagTool tool;

    @BeforeEach
    void setUp() {
        WorldIndexManager.createWorld(tmpDir, WORLD, "删标签世界");
        MapData mapData = new MapData(
                30,
                false,
                Map.of(
                        MapData.hexKey(0, 0),
                        new MapData.HexCell(
                                "#6CC261", "plains", null, null, "王都", 0, Map.of(), Map.of("a", "1", "b", "2"))),
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
        tool = new GsimapRemoveHexTagTool(mapService);
    }

    private ToolResult execute(Map<String, String> args) {
        return tool.execute(new ToolCall("gsimap_remove_hex_tag", args));
    }

    @Test
    @DisplayName("happy：删单 key → ok 且结果不含已删 key")
    void removeTagSucceeds() {
        ToolResult result = execute(Map.of("worldId", WORLD, "nodeId", "n0000", "q", "0", "r", "0", "tagKey", "a"));
        assertTrue(result.success(), "should succeed: " + result.error());
        assertEquals(1, result.items().size());
        ToolResult.Item item = result.items().get(0);
        assertEquals("gsimap:hex:0_0", item.path(), "item path is the hex key");
        assertTrue(item.snippet().contains("removed=a"), "snippet should mention removed key: " + item.snippet());
        assertFalse(item.snippet().contains("a=1"), "snippet should not contain the removed tag: " + item.snippet());
    }

    @Test
    @DisplayName("tagKey 不存在 → fail")
    void unknownTagFails() {
        ToolResult result = execute(Map.of("worldId", WORLD, "nodeId", "n0000", "q", "0", "r", "0", "tagKey", "nope"));
        assertFalse(result.success(), "should fail for an unknown tag key");
        assertEquals("Tag not found: nope on hex 0_0", result.error());
    }

    @Test
    @DisplayName("hex 不存在 → fail")
    void hexNotFoundFails() {
        ToolResult result = execute(Map.of("worldId", WORLD, "nodeId", "n0000", "q", "99", "r", "-99", "tagKey", "a"));
        assertFalse(result.success(), "should fail for a missing hex");
        assertEquals("Hex not found: 99_-99", result.error());
    }

    @Test
    @DisplayName("缺 nodeId → fail 含 nodeId is required")
    void missingNodeIdFails() {
        ToolResult result = execute(Map.of("worldId", WORLD, "q", "0", "r", "0", "tagKey", "a"));
        assertFalse(result.success(), "should reject a call without nodeId");
        assertTrue(result.error().contains("nodeId is required"), "error: " + result.error());
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
