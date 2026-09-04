package com.gsim.agent.tools.map;

import static org.junit.jupiter.api.Assertions.*;

import com.gsim.agentsmanager.ref.ResolverContext;
import com.gsim.agentsmanager.ref.ResolverRegistry;
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
 * GsimapResolver — {@code gsimap:} 前缀地址（region/hex/city/terrain）解析测试。
 */
@DisplayName("GsimapResolver — gsimap: 地址解析")
class GsimapResolverTest {

    private static final String WORLD = "mapworld";

    @TempDir
    Path tmpDir;

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
        registry = ResolverRegistry.createWithBuiltins();
        registry.register(new GsimapResolver(mapService));
    }

    private ResolverContext ctx() {
        return ResolverContext.of(tmpDir, WORLD, null, null, null);
    }

    @Test
    @DisplayName("gsimap:region:{name} 解析省/区域")
    void regionResolves() {
        var result = registry.resolve("gsimap:region:迷雾森林", ctx());
        assertEquals("gsimap", result.source());
        assertEquals("gsimap:region:迷雾森林", result.id());
        assertTrue(result.content().contains("迷雾森林"), "content: " + result.content());
    }

    @Test
    @DisplayName("gsimap:hex:{q}_{r} 解析单格")
    void hexResolves() {
        var result = registry.resolve("gsimap:hex:0_0", ctx());
        assertEquals("gsimap", result.source());
        assertEquals("gsimap:hex:0_0", result.id());
        assertTrue(result.content().contains("0_0"), "content: " + result.content());
    }

    @Test
    @DisplayName("gsimap:hex:{q}_{r}:tag:{tag_key} 解析单标签")
    void hexTagResolves() {
        var result = registry.resolve("gsimap:hex:0_0:tag:煤炭资源", ctx());
        assertEquals("gsimap", result.source());
        assertEquals("gsimap:hex:0_0:tag:煤炭资源", result.id(), "id 用完整 tag 地址");
        assertEquals("gsimap:hex:0_0:tag:煤炭资源", result.title(), "title 用完整 tag 地址");
        assertTrue(result.content().contains("tagKey"), "content: " + result.content());
        assertTrue(result.content().contains("煤炭资源"), "content: " + result.content());
        assertTrue(result.content().contains("23662吨"), "content: " + result.content());
    }

    @Test
    @DisplayName("hex tag 不存在抛 IllegalArgumentException 且消息含 Tag not found")
    void missingHexTagThrows() {
        var e = assertThrows(IllegalArgumentException.class, () -> registry.resolve("gsimap:hex:0_0:tag:铁矿", ctx()));
        assertTrue(e.getMessage().contains("Tag not found"), "msg: " + e.getMessage());
        assertTrue(e.getMessage().contains("铁矿"), "msg: " + e.getMessage());
    }

    @Test
    @DisplayName("空 tagKey 抛 Invalid hex tag address")
    void blankHexTagKeyThrows() {
        var e = assertThrows(IllegalArgumentException.class, () -> registry.resolve("gsimap:hex:0_0:tag:", ctx()));
        assertTrue(e.getMessage().contains("Invalid hex tag address"), "msg: " + e.getMessage());
    }

    @Test
    @DisplayName("非 tag: 前缀子段抛 Invalid hex tag address")
    void nonTagPrefixThrows() {
        var e = assertThrows(IllegalArgumentException.class, () -> registry.resolve("gsimap:hex:0_0:foo:bar", ctx()));
        assertTrue(e.getMessage().contains("Invalid hex tag address"), "msg: " + e.getMessage());
    }

    @Test
    @DisplayName("不存在的 hex 带 tag 子段同样抛 Hex not found")
    void missingHexWithTagThrows() {
        var e = assertThrows(IllegalArgumentException.class, () -> registry.resolve("gsimap:hex:99_99:tag:x", ctx()));
        assertTrue(e.getMessage().contains("Hex not found"), "msg: " + e.getMessage());
    }

    @Test
    @DisplayName("gsimap:city:{name} 解析城市")
    void cityResolves() {
        var result = registry.resolve("gsimap:city:洛阳", ctx());
        assertEquals("gsimap", result.source());
        assertEquals("gsimap:city:洛阳", result.id());
        assertTrue(result.content().contains("洛阳"), "content: " + result.content());
    }

    @Test
    @DisplayName("gsimap:terrain:{key} 解析地形定义")
    void terrainResolves() {
        var result = registry.resolve("gsimap:terrain:plains", ctx());
        assertEquals("gsimap", result.source());
        assertEquals("gsimap:terrain:plains", result.id());
        assertTrue(result.content().contains("平原"), "content: " + result.content());
    }

    @Test
    @DisplayName("未知实体类型抛 IllegalArgumentException 并列出合法类型")
    void unknownTypeThrowsWithValidTypes() {
        var e = assertThrows(IllegalArgumentException.class, () -> registry.resolve("gsimap:bogus:x", ctx()));
        assertTrue(e.getMessage().contains("Unknown gsimap entity type"), "msg: " + e.getMessage());
        assertTrue(e.getMessage().contains("region"), "msg: " + e.getMessage());
        assertTrue(e.getMessage().contains("hex"), "msg: " + e.getMessage());
        assertTrue(e.getMessage().contains("city"), "msg: " + e.getMessage());
        assertTrue(e.getMessage().contains("terrain"), "msg: " + e.getMessage());
    }

    @Test
    @DisplayName("不存在的实体抛 IllegalArgumentException")
    void missingEntityThrows() {
        assertThrows(IllegalArgumentException.class, () -> registry.resolve("gsimap:region:不存在之地", ctx()));
        assertThrows(IllegalArgumentException.class, () -> registry.resolve("gsimap:hex:99_99", ctx()));
    }

    @Test
    @DisplayName("空 worldId 抛 IllegalStateException")
    void blankWorldIdThrows() {
        ResolverContext blank = ResolverContext.of(tmpDir, " ", null, null, null);
        assertThrows(IllegalStateException.class, () -> registry.resolve("gsimap:region:迷雾森林", blank));
    }
}
