package com.gsim.map.map;

import static org.junit.jupiter.api.Assertions.*;

import com.gsim.core.util.JsonUtils;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * T1 — HexCell.tags model field.
 *
 * <p>Verifies the defensive constructor (null → empty frozen map), the
 * {@code of(String)} factory default, JSON backward compatibility (old map
 * files without a {@code tags} field must deserialize to an empty map), a
 * serialization round-trip, and that a tags-only change is detected by
 * {@link MapDiff#compute} through the record {@code equals} chain.
 */
class HexCellTagsTest {

    private static MapData mapWithHex(String hexKey, MapData.HexCell cell) {
        return new MapData(
                30,
                false,
                Map.of(hexKey, cell),
                List.of(),
                Map.of(),
                Map.of(),
                List.of(),
                List.of(),
                MapData.TerrainType.defaults(),
                List.of(),
                Map.of(),
                Map.of());
    }

    @Test
    @DisplayName("构造器 null tags → 空不可变 map（冻结生效）")
    void nullTagsBecomesEmptyFrozenMap() {
        MapData.HexCell cell = new MapData.HexCell(
                "#228B22", "forest", "祭", null, "神秘祭坛", 0, Map.of(), null);

        assertNotNull(cell.tags());
        assertTrue(cell.tags().isEmpty());
        assertThrows(UnsupportedOperationException.class, () -> cell.tags().put("k", "v"));
    }

    @Test
    @DisplayName("of(String) 工厂产出 tags 为空")
    void ofFactoryProducesEmptyTags() {
        MapData.HexCell cell = MapData.HexCell.of("#228B22");
        assertTrue(cell.tags().isEmpty());

        MapData.HexCell cellWithTerrain = MapData.HexCell.of("#6CC261", "plains");
        assertTrue(cellWithTerrain.tags().isEmpty());
    }

    @Test
    @DisplayName("旧 JSON 无 tags 字段 → 反序列化成功且 tags 为空")
    void oldJsonWithoutTagsDeserializesToEmptyTags() throws Exception {
        String legacyJson = "{\"color\":\"#228B22\",\"terrain\":\"forest\",\"symbol\":\"祭\","
                + "\"symbolColor\":null,\"description\":\"神秘祭坛\",\"riverMask\":0,\"edgeTags\":{}}";

        MapData.HexCell cell = JsonUtils.MAPPER.readValue(legacyJson, MapData.HexCell.class);

        assertNotNull(cell);
        assertEquals("forest", cell.terrain());
        assertNotNull(cell.tags());
        assertTrue(cell.tags().isEmpty());
    }

    @Test
    @DisplayName("tags 含值 → 序列化→反序列化往返一致")
    void tagsRoundTripThroughJson() throws Exception {
        MapData.HexCell cell = new MapData.HexCell(
                "#228B22",
                "forest",
                null,
                null,
                "",
                0,
                Map.of(3, List.of("river")),
                Map.of("煤炭资源", "23662吨", "首都", "⭐"));

        String json = JsonUtils.MAPPER.writeValueAsString(cell);
        MapData.HexCell back = JsonUtils.MAPPER.readValue(json, MapData.HexCell.class);

        assertEquals(cell, back);
        assertEquals(cell.tags(), back.tags());
        assertEquals(Map.of("煤炭资源", "23662吨", "首都", "⭐"), back.tags());
    }

    @Test
    @DisplayName("仅修改 tags → MapDiff.compute 检测 changed 含该 hex")
    void tagsOnlyChangeIsDetectedByMapDiff() {
        MapData.HexCell base = new MapData.HexCell(
                "#228B22", "forest", null, null, "", 0, Map.of(), Map.of("a", "1"));
        MapData.HexCell changed = new MapData.HexCell(
                "#228B22", "forest", null, null, "", 0, Map.of(), Map.of("a", "2"));

        MapData parent = mapWithHex("0_0", base);
        MapData child = mapWithHex("0_0", changed);

        MapDiff diff = MapDiff.compute("n0000", parent, child);

        assertTrue(diff.changed().containsKey("0_0"), "tags-only change must be diffed");
        assertEquals(child.hexes().get("0_0"), diff.changed().get("0_0"));

        // Identical tags → no diff (record equals covers the new component)
        MapData sameChild = mapWithHex("0_0", base);
        MapDiff noChange = MapDiff.compute("n0000", parent, sameChild);
        assertTrue(noChange.changed().isEmpty());
    }
}
