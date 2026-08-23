package com.gsim.map.service;

import static org.junit.jupiter.api.Assertions.*;

import com.gsim.map.map.MapData;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * TerrainTextRenderer region / tag render modes — 纯静态渲染测试。
 *
 * <p>覆盖：region 分组字符与 legend、tagFilter 白名单限定、重合 hex 的 ※ 与明细、
 * tag 存在性渲染、无参与 province 时的空结果。
 */
@DisplayName("TerrainTextRenderer — region / tag 渲染模式")
class TerrainTextRendererRegionTest {

    private static final MapData.Province WANG_A =
            new MapData.Province(List.of("0_0", "1_0", "-1_0"), "#ff0000", "王国A", "东境");
    private static final MapData.Province WANG_B = new MapData.Province(List.of("0_1", "0_-1"), "#00ff00", "王国B", "西境");
    private static final MapData.Province WANG_C =
            new MapData.Province(List.of("1_-1", "-1_1"), "#0000ff", "王国C", "南境");

    /** radius 1 内全部 7 个 hex 的底图（中心 0_0）。 */
    private MapData mapWithProvinces(MapData.Province... provinces) {
        Map<String, MapData.HexCell> hexes = Map.of(
                "0_0", MapData.HexCell.of("#6CC261", "plains"),
                "1_0", MapData.HexCell.of("#6CC261", "plains"),
                "-1_0", MapData.HexCell.of("#6CC261", "plains"),
                "0_1", MapData.HexCell.of("#228B22", "forest"),
                "0_-1", MapData.HexCell.of("#228B22", "forest"),
                "1_-1", MapData.HexCell.of("#228B22", "forest"),
                "-1_1", MapData.HexCell.of("#228B22", "forest"));
        Map<String, MapData.Province> provinceMap = new java.util.LinkedHashMap<>();
        for (MapData.Province p : provinces) {
            String name = p.tag().isEmpty() ? "匿名" : p.tag();
            provinceMap.put(name, p);
        }
        return new MapData(
                30,
                false,
                hexes,
                List.of(),
                provinceMap,
                Map.of(),
                List.of(),
                List.of(),
                MapData.TerrainType.defaults(),
                List.of(),
                Map.of(),
                Map.of());
    }

    @Test
    @DisplayName("region 渲染：不同 province 用不同字符，背景 hex 为 ·")
    void regionUsesPerProvinceChars() {
        MapData map = mapWithProvinces(WANG_A, WANG_B);
        String text = TerrainTextRenderer.renderRegions(map, 0, 0, 1, null);
        assertTrue(text.contains("A"), "text: " + text);
        assertTrue(text.contains("B"), "text: " + text);
        assertTrue(text.contains("·"), "text: " + text);
        assertFalse(text.contains("C"));
        assertFalse(text.contains("※"));
    }

    @Test
    @DisplayName("region legend：标注 字符 = 区域名 (tag=..., N格)")
    void regionLegendListsProvinces() {
        MapData map = mapWithProvinces(WANG_A, WANG_B, WANG_C);
        Map<String, String> charMap = TerrainTextRenderer.buildRegionCharMap(map, 0, 0, 1, null);
        assertEquals(3, charMap.size());
        assertEquals("A", charMap.get("王国A"));
        assertEquals("B", charMap.get("王国B"));
        assertEquals("C", charMap.get("王国C"));

        String legend = TerrainTextRenderer.legendRegions(charMap, map, 0);
        assertTrue(legend.contains("A = 王国A (tag=王国A, 3格)"), "legend: " + legend);
        assertTrue(legend.contains("B = 王国B (tag=王国B, 2格)"), "legend: " + legend);
        assertFalse(legend.contains("※"));
    }

    @Test
    @DisplayName("tagFilter 限定：只渲染白名单 tag 的 province，其余为 ·")
    void tagFilterRestrictsProvinces() {
        MapData map = mapWithProvinces(WANG_A, WANG_B, WANG_C);
        String text = TerrainTextRenderer.renderRegions(map, 0, 0, 1, Set.of("王国A"));
        assertTrue(text.contains("A"), "text: " + text);
        assertFalse(text.contains("B"), "text: " + text);
        assertFalse(text.contains("C"), "text: " + text);
        assertFalse(text.contains("※"));
        long background = text.chars().filter(c -> c == '·').count();
        assertEquals(4, background, "text: " + text); // B/C 的 4 格均为背景

        Map<String, String> charMap = TerrainTextRenderer.buildRegionCharMap(map, 0, 0, 1, Set.of("王国A"));
        assertEquals(Set.of("王国A"), charMap.keySet());
    }

    @Test
    @DisplayName("重合：共享 hex 渲染为 ※ 且明细列出")
    void overlapHexShowsMarkerAndDetail() {
        MapData.Province sharedA = new MapData.Province(List.of("0_0", "1_0"), "#ff0000", "王国A", "东境");
        MapData.Province sharedB = new MapData.Province(List.of("0_0", "0_1"), "#00ff00", "王国B", "西境");
        MapData map = mapWithProvinces(sharedA, sharedB);

        TerrainTextRenderer.RegionRenderResult result = TerrainTextRenderer.renderRegionsDetailed(map, 0, 0, 1, null);
        assertTrue(result.text().contains("※"), "text: " + result.text());
        assertEquals(1, result.overlapLines().size(), "lines: " + result.overlapLines());
        assertTrue(
                result.overlapLines().get(0).contains("重合 hex: 0_0 ∈ {王国A, 王国B}"),
                "line: " + result.overlapLines().get(0));

        String legend = TerrainTextRenderer.legendRegions(
                result.regionCharMap(), map, result.overlapLines().size());
        assertTrue(legend.contains("※ = 区域重合 (1 个 hex 属于多个区域)"), "legend: " + legend);
    }

    @Test
    @DisplayName("tag 模式：有 tagKey 的 hex 为 #，其余为 ·")
    void tagPresenceMarksHasAndHasNot() {
        MapData map = new MapData(
                30,
                false,
                Map.of(
                        "0_0",
                        new MapData.HexCell("#6CC261", "plains", null, null, "", 0, Map.of(), Map.of("资源", "金")),
                        "1_0",
                        new MapData.HexCell("#228B22", "forest", null, null, "", 0, Map.of(), Map.of())),
                List.of(),
                Map.of(),
                Map.of(),
                List.of(),
                List.of(),
                MapData.TerrainType.defaults(),
                List.of(),
                Map.of(),
                Map.of());

        String text = TerrainTextRenderer.renderTagPresence(map, 0, 0, 1, "资源");
        assertTrue(text.contains("#"), "text: " + text);
        assertTrue(text.contains("·"), "text: " + text);

        String legend = TerrainTextRenderer.legendTagPresence("资源");
        assertTrue(legend.contains("# = 有标签 资源"), "legend: " + legend);
        assertTrue(legend.contains("· = 无标签 资源"), "legend: " + legend);
    }

    @Test
    @DisplayName("无参与 province：返回 (no regions in range)")
    void noRegionsInRange() {
        MapData empty = new MapData(
                30,
                false,
                Map.of("0_0", MapData.HexCell.of("#6CC261", "plains")),
                List.of(),
                Map.of(),
                Map.of(),
                List.of(),
                List.of(),
                MapData.TerrainType.defaults(),
                List.of(),
                Map.of(),
                Map.of());
        assertEquals("(no regions in range)", TerrainTextRenderer.renderRegions(empty, 0, 0, 1, null));
        assertEquals("(no regions in range)", TerrainTextRenderer.renderRegions(empty, 0, 0, 1, Set.of("王国A")));
    }
}
