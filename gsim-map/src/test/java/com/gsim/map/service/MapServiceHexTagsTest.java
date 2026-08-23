package com.gsim.map.service;

import static org.junit.jupiter.api.Assertions.*;

import com.gsim.core.worldinfo.NodeSnapshot;
import com.gsim.core.worldinfo.loader.NodeLoader;
import com.gsim.map.map.MapData;
import com.gsim.map.map.MapDiff;
import com.gsim.map.map.MapStore;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * MapService.setHexTags / removeHexTag — hex 标签写路径测试。
 *
 * <p>覆盖：tags 合并语义（不删未提及 key）、description null 保留/非 null 覆盖、
 * hex 不存在抛 IllegalArgumentException、removeHexTag 删单 key、
 * child node（非 root）保存产生 diff（MapStore.loadDiff 非空）。
 */
class MapServiceHexTagsTest {

    private static final String WORLD = "testworld";

    @TempDir
    Path tmpDir;

    private void writeNode(String nodeId, String parentId) {
        NodeSnapshot node = new NodeSnapshot(
                nodeId,
                parentId,
                nodeId.equals("n0000") ? 0 : 1,
                "t1",
                "simulated",
                "2026-01-01T00:00:00Z",
                Map.of(),
                new LinkedHashMap<>());
        NodeLoader.save(NodeLoader.nodeFile(tmpDir, WORLD, nodeId), node);
    }

    private MapData mapWithHex(String description, Map<String, String> tags) {
        return new MapData(
                30,
                false,
                Map.of(
                        MapData.hexKey(0, 0),
                        new MapData.HexCell("#6CC261", "plains", null, null, description, 0, Map.of(), tags)),
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

    private void saveRoot(MapData mapData) {
        MapStore.saveFull(tmpDir, WORLD, "n0000", mapData);
    }

    @Test
    void setHexTagsMergesTagsWithoutRemovingUnmentionedKeys() {
        writeNode("n0000", null);
        saveRoot(mapWithHex("王都", Map.of("a", "1")));
        MapService service = new MapService(tmpDir);

        MapData after1 = service.setHexTags(WORLD, "n0000", 0, 0, null, Map.of("b", "2"));
        assertEquals(Map.of("a", "1", "b", "2"), after1.hexes().get("0_0").tags(), "merge adds new key");

        MapData after2 = service.setHexTags(WORLD, "n0000", 0, 0, null, Map.of("a", "9"));
        assertEquals(Map.of("a", "9", "b", "2"), after2.hexes().get("0_0").tags(), "merge overwrites existing key");
    }

    @Test
    void setHexTagsNullTagsLeavesTagsUntouched() {
        writeNode("n0000", null);
        saveRoot(mapWithHex("王都", Map.of("a", "1")));
        MapService service = new MapService(tmpDir);

        MapData after = service.setHexTags(WORLD, "n0000", 0, 0, null, null);
        assertEquals(Map.of("a", "1"), after.hexes().get("0_0").tags(), "null tags must not clear existing tags");
    }

    @Test
    void setHexTagsDescriptionNullPreservesOriginalNonNullOverwrites() {
        writeNode("n0000", null);
        saveRoot(mapWithHex("原描述", Map.of()));
        MapService service = new MapService(tmpDir);

        MapData kept = service.setHexTags(WORLD, "n0000", 0, 0, null, Map.of());
        assertEquals("原描述", kept.hexes().get("0_0").description(), "null description keeps original");

        MapData updated = service.setHexTags(WORLD, "n0000", 0, 0, "新描述", Map.of());
        assertEquals("新描述", updated.hexes().get("0_0").description(), "non-null description overwrites");
    }

    @Test
    void setHexTagsHexNotFoundThrows() {
        writeNode("n0000", null);
        saveRoot(mapWithHex("王都", Map.of("a", "1")));
        MapService service = new MapService(tmpDir);

        IllegalArgumentException e = assertThrows(
                IllegalArgumentException.class,
                () -> service.setHexTags(WORLD, "n0000", 9, -9, null, Map.of("b", "2")));
        assertEquals("Hex not found: 9_-9", e.getMessage());
    }

    @Test
    void removeHexTagRemovesSingleKey() {
        writeNode("n0000", null);
        saveRoot(mapWithHex("王都", Map.of("a", "1", "b", "2")));
        MapService service = new MapService(tmpDir);

        MapData after = service.removeHexTag(WORLD, "n0000", 0, 0, "a");
        assertEquals(Map.of("b", "2"), after.hexes().get("0_0").tags(), "only the requested key is removed");
    }

    @Test
    void removeHexTagRemovingLastKeyLeavesEmptyMapNotNull() {
        writeNode("n0000", null);
        saveRoot(mapWithHex("王都", Map.of("a", "1")));
        MapService service = new MapService(tmpDir);

        MapData after = service.removeHexTag(WORLD, "n0000", 0, 0, "a");
        Map<String, String> tags = after.hexes().get("0_0").tags();
        assertNotNull(tags, "tags must not be null");
        assertTrue(tags.isEmpty(), "tags should be empty after removing the last key");
    }

    @Test
    void removeHexTagUnknownKeyThrows() {
        writeNode("n0000", null);
        saveRoot(mapWithHex("王都", Map.of("a", "1")));
        MapService service = new MapService(tmpDir);

        IllegalArgumentException e =
                assertThrows(IllegalArgumentException.class, () -> service.removeHexTag(WORLD, "n0000", 0, 0, "nope"));
        assertEquals("Tag not found: nope on hex 0_0", e.getMessage());
    }

    @Test
    void removeHexTagHexNotFoundThrows() {
        writeNode("n0000", null);
        saveRoot(mapWithHex("王都", Map.of("a", "1")));
        MapService service = new MapService(tmpDir);

        IllegalArgumentException e =
                assertThrows(IllegalArgumentException.class, () -> service.removeHexTag(WORLD, "n0000", 9, -9, "a"));
        assertEquals("Hex not found: 9_-9", e.getMessage());
    }

    @Test
    void childNodeHexTagSaveProducesDiff() {
        writeNode("n0000", null);
        writeNode("n0001", "n0000");
        saveRoot(mapWithHex("王都", Map.of()));
        MapService service = new MapService(tmpDir);

        service.setHexTags(WORLD, "n0001", 0, 0, null, Map.of("煤炭资源", "23662吨"));

        MapDiff diff = MapStore.loadDiff(tmpDir, WORLD, "n0001");
        assertNotNull(diff, "child diff must exist");
        assertTrue(
                diff.changed().containsKey("0_0"),
                "changed hexes must contain 0_0: " + diff.changed().keySet());
        assertEquals("23662吨", diff.changed().get("0_0").tags().get("煤炭资源"), "diff cell carries the new tag");
    }
}
