package com.gsim.map.tools.map;

import static org.junit.jupiter.api.Assertions.*;

import com.gsim.agentsmanager.ref.ResolverContext;
import com.gsim.agentsmanager.ref.ResolverRegistry;
import com.gsim.agentsmanager.tool.ToolCall;
import com.gsim.agentsmanager.tool.ToolResult;
import com.gsim.core.worldinfo.loader.WorldIndexManager;
import com.gsim.docslib.util.JsonUtils;
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
 * GsimapQueryByAddressTool — {@code gsimap:hex:{q}_{r}:tag:{tag_key}} 地址解析，
 * 与 {@link GsimapResolver} 行为一致。
 */
@DisplayName("GsimapQueryByAddressTool — gsimap:hex:...:tag:... 地址解析")
class GsimapQueryByAddressToolTest {

    private static final String WORLD = "mapworld";

    @TempDir
    Path tmpDir;

    private GsimapQueryByAddressTool tool;
    private ResolverRegistry registry;

    @BeforeEach
    void setUp() {
        // 临时世界：world.json + 根节点 n0000 + active.json
        WorldIndexManager.createWorld(tmpDir, WORLD, "地图世界");

        MapData mapData = new MapData(
                30,
                false,
                Map.of(
                        MapData.hexKey(0, 0),
                        new MapData.HexCell(
                                "#6CC261", "plains", null, null, "", 0, Map.of(), Map.of("煤炭资源", "23662吨"))),
                List.of(),
                Map.of("迷雾森林", new MapData.Province(List.of(MapData.hexKey(0, 0)), "#228B22", "forest", "浓雾笼罩", "")),
                Map.of("洛阳", new MapData.City(0, 0, "洛阳", "都城")),
                List.of(),
                List.of(),
                MapData.TerrainType.defaults(),
                List.of(),
                Map.of(),
                Map.of());
        MapStore.saveFull(tmpDir, WORLD, "n0000", mapData);

        MapService mapService = new MapService(tmpDir);
        tool = new GsimapQueryByAddressTool(mapService);

        // 一致性对比基准：同一 GsimapResolver 注册进 registry
        registry = ResolverRegistry.createWithBuiltins();
        registry.register(new GsimapResolver(mapService));
    }

    private ToolResult query(String address) {
        return tool.execute(new ToolCall(
                "gsimap_query_by_address", Map.of("worldId", WORLD, "nodeId", "n0000", "address", address)));
    }

    @Test
    @DisplayName("gsimap:hex:{q}_{r}:tag:{tag_key} 返回完整 tag 地址与 tagKey/tagValue")
    void hexTagResolves() {
        ToolResult r = query("gsimap:hex:0_0:tag:煤炭资源");
        assertTrue(r.success(), "should succeed: " + r.error());
        assertEquals(1, r.items().size());
        ToolResult.Item item = r.items().get(0);
        assertEquals("gsimap:hex:0_0:tag:煤炭资源", item.path(), "Item path 必须为完整 tag 地址");
        assertTrue(item.snippet().contains("tagKey"), "snippet: " + item.snippet());
        assertTrue(item.snippet().contains("煤炭资源"), "snippet: " + item.snippet());
        assertTrue(item.snippet().contains("23662吨"), "snippet: " + item.snippet());
    }

    @Test
    @DisplayName("tag 缺失返回 ToolResult.fail 且消息含 Tag not found")
    void missingTagFails() {
        ToolResult r = query("gsimap:hex:0_0:tag:铁矿");
        assertFalse(r.success());
        assertTrue(r.error().contains("Tag not found"), "error: " + r.error());
    }

    @Test
    @DisplayName("空 tagKey 返回 ToolResult.fail Invalid hex tag address")
    void blankTagKeyFails() {
        ToolResult r = query("gsimap:hex:0_0:tag:");
        assertFalse(r.success());
        assertTrue(r.error().contains("Invalid hex tag address"), "error: " + r.error());
    }

    @Test
    @DisplayName("非 tag: 前缀子段返回 ToolResult.fail Invalid hex tag address")
    void nonTagPrefixFails() {
        ToolResult r = query("gsimap:hex:0_0:foo:bar");
        assertFalse(r.success());
        assertTrue(r.error().contains("Invalid hex tag address"), "error: " + r.error());
    }

    @Test
    @DisplayName("3 段 gsimap:hex:{q}_{r} 地址回归通过")
    void plainHexRegression() {
        ToolResult r = query("gsimap:hex:0_0");
        assertTrue(r.success(), "should succeed: " + r.error());
        ToolResult.Item item = r.items().get(0);
        assertEquals("gsimap:hex:0_0", item.path());
        assertFalse(item.snippet().contains("tagKey"), "snippet 不应含 tagKey: " + item.snippet());
    }

    @Test
    @DisplayName("同一 tag 地址在 Resolver 与 QueryByAddressTool 产出一致 tagKey/tagValue")
    void consistentWithResolver() {
        var resolved = registry.resolve("gsimap:hex:0_0:tag:煤炭资源", ResolverContext.of(tmpDir, WORLD, null, null, null));
        ToolResult r = query("gsimap:hex:0_0:tag:煤炭资源");
        assertTrue(r.success(), "should succeed: " + r.error());

        Map<?, ?> resolverMap = JsonUtils.fromJson(resolved.content(), Map.class);
        Map<?, ?> toolMap = JsonUtils.fromJson(r.items().get(0).snippet(), Map.class);
        assertEquals("煤炭资源", resolverMap.get("tagKey"));
        assertEquals("23662吨", resolverMap.get("tagValue"));
        assertEquals(resolverMap.get("tagKey"), toolMap.get("tagKey"), "tagKey 必须一致");
        assertEquals(resolverMap.get("tagValue"), toolMap.get("tagValue"), "tagValue 必须一致");
    }
}
