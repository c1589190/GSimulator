package com.gsim.map.service;

import static org.junit.jupiter.api.Assertions.*;

import com.gsim.core.worldinfo.NodeSnapshot;
import com.gsim.core.worldinfo.loader.NodeLoader;
import com.gsim.map.config.MapConfig;
import com.gsim.map.map.MapData;
import com.gsim.map.map.MapStore;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Regression test: saving a map for a CHILD node whose parent (root) node has
 * no map yet must auto-create an empty baseline map for the parent instead of
 * NPE-ing with "Cannot invoke MapData.hexes() because parent is null".
 */
class MapServiceChildNodeSaveTest {

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

    @Test
    void saveMapForChildWithNoParentMapCreatesEmptyBaselineAndDiff() {
        // Root n0000 (isRoot=true) and child n0001 (parentId=n0000) — root has NO map file.
        writeNode("n0000", null);
        writeNode("n0001", "n0000");

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

        // Previously NPE'd: resolve(n0000) -> null -> MapDiff.compute(..., null, ...)
        new MapService(tmpDir, MapConfig.defaults()).saveMap(WORLD, "n0001", mapData);

        // (2) Parent now has a full (empty) baseline map file.
        MapData baseline = MapStore.loadFull(tmpDir, WORLD, "n0000");
        assertNotNull(baseline, "root baseline map must be auto-created");
        assertTrue(baseline.hexes().isEmpty(), "baseline must be empty");

        // (3) Child has a diff file recorded against the empty baseline.
        assertNotNull(MapStore.loadDiff(tmpDir, WORLD, "n0001"), "child diff must exist");
    }
}
