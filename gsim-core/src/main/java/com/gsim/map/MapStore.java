package com.gsim.map;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Reads and writes map files in the GSim worlds directory structure.
 *
 * <p>File layout:
 * <pre>
 *   worlds/{worldId}/nodes/{nodeId}_map.json   ← map data for a specific node
 * </pre>
 *
 * <p>Root node (n0000) stores a full {@link MapData}.
 * Child nodes store a {@link MapDiff} (optional — if absent, inherits parent unchanged).
 */
public final class MapStore {

    private static final Logger log = LoggerFactory.getLogger(MapStore.class);
    private static final ObjectMapper MAPPER = new ObjectMapper()
        .enable(SerializationFeature.INDENT_OUTPUT);

    private MapStore() {}

    // ── Path helpers ─────────────────────────────────────

    /** worlds/{worldId}/nodes/{nodeId}_map.json */
    public static Path mapFile(Path worldsDir, String worldId, String nodeId) {
        return worldsDir.resolve(worldId).resolve("nodes").resolve(nodeId + "_map.json");
    }

    /** Check if a map file exists for the given node. */
    public static boolean exists(Path worldsDir, String worldId, String nodeId) {
        return Files.exists(mapFile(worldsDir, worldId, nodeId));
    }

    // ── Full map (root nodes) ────────────────────────────

    /** Load a full MapData from a node's _map.json. Returns null if absent. */
    public static MapData loadFull(Path worldsDir, String worldId, String nodeId) {
        Path file = mapFile(worldsDir, worldId, nodeId);
        if (!Files.exists(file)) return null;
        try {
            return MAPPER.readValue(file.toFile(), MapData.class);
        } catch (IOException e) {
            log.error("Failed to load map: {}", file, e);
            return null;
        }
    }

    /** Save a full MapData to a node's _map.json. */
    public static void saveFull(Path worldsDir, String worldId, String nodeId, MapData data) {
        Path file = mapFile(worldsDir, worldId, nodeId);
        try {
            Files.createDirectories(file.getParent());
            MAPPER.writeValue(file.toFile(), data);
            log.debug("Saved full map: {}", file);
        } catch (IOException e) {
            log.error("Failed to save map: {}", file, e);
            throw new RuntimeException("Failed to save map: " + file, e);
        }
    }

    // ── Diff map (child nodes) ───────────────────────────

    /** Load a MapDiff from a node's _map.json. Returns null if absent or if it's a full map. */
    public static MapDiff loadDiff(Path worldsDir, String worldId, String nodeId) {
        Path file = mapFile(worldsDir, worldId, nodeId);
        if (!Files.exists(file)) return null;
        try {
            // Try parsing as MapDiff first (has parentNodeId field)
            var node = MAPPER.readTree(file.toFile());
            if (node.has("parentNodeId")) {
                return MAPPER.treeToValue(node, MapDiff.class);
            }
            // It's a full MapData (root node format), not a diff
            return null;
        } catch (IOException e) {
            log.error("Failed to load map diff: {}", file, e);
            return null;
        }
    }

    /** Save a MapDiff to a node's _map.json. */
    public static void saveDiff(Path worldsDir, String worldId, String nodeId, MapDiff diff) {
        Path file = mapFile(worldsDir, worldId, nodeId);
        try {
            Files.createDirectories(file.getParent());
            MAPPER.writeValue(file.toFile(), diff);
            log.debug("Saved map diff: {}", file);
        } catch (IOException e) {
            log.error("Failed to save map diff: {}", file, e);
            throw new RuntimeException("Failed to save map diff: " + file, e);
        }
    }
}
