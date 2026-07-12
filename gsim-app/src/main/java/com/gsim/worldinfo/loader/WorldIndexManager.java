package com.gsim.worldinfo.loader;

import com.gsim.util.JsonUtils;
import com.gsim.worldinfo.Checkpoint;
import com.gsim.worldinfo.NodeSnapshot;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Manages _index.json and world.json.
 */
public final class WorldIndexManager {

    private WorldIndexManager() {}

    public record WorldEntry(String id, String name, String createdAt) {}

    // ---- _index.json ----

    public static Path indexFile(Path worldsDir) {
        return worldsDir.resolve("_index.json");
    }

    public static List<WorldEntry> listWorlds(Path worldsDir) {
        Path file = indexFile(worldsDir);
        if (!Files.exists(file)) return List.of();
        try {
            WorldEntry[] arr = JsonUtils.fromJson(Files.readString(file), WorldEntry[].class);
            return arr != null ? List.of(arr) : List.of();
        } catch (IOException e) {
            return List.of();
        }
    }

    private static void saveIndex(Path worldsDir, List<WorldEntry> entries) {
        try {
            Files.createDirectories(worldsDir);
            Files.writeString(indexFile(worldsDir), JsonUtils.toJson(entries));
        } catch (IOException e) {
            throw new RuntimeException("Failed to write _index.json", e);
        }
    }

    // ---- world.json ----

    public record WorldMeta(String id, String name, String createdAt, String currentNodeId) {}

    public static Path worldFile(Path worldsDir, String worldId) {
        return worldsDir.resolve(worldId).resolve("world.json");
    }

    public static WorldMeta loadWorldMeta(Path worldsDir, String worldId) {
        Path file = worldFile(worldsDir, worldId);
        if (!Files.exists(file)) return null;
        try {
            return JsonUtils.fromJson(Files.readString(file), WorldMeta.class);
        } catch (IOException e) {
            throw new RuntimeException("Failed to load world.json: " + worldId, e);
        }
    }

    // ---- creation ----

    /**
     * Create a new world. Generates n0000 root node with empty checkpoints.
     * Returns the world meta.
     */
    public static WorldMeta createWorld(Path worldsDir, String worldId, String name) {
        String now = Instant.now().toString();

        // world.json
        WorldMeta meta = new WorldMeta(worldId, name, now, "n0000");
        try {
            Files.createDirectories(worldFile(worldsDir, worldId).getParent());
            Files.writeString(worldFile(worldsDir, worldId), JsonUtils.toJson(meta));
        } catch (IOException e) {
            throw new RuntimeException("Failed to create world.json", e);
        }

        // n0000.json (empty root node)
        NodeSnapshot root = new NodeSnapshot("n0000", null, 0, "时间原点", "initial", now,
            Map.of("worldview", new Checkpoint("世界观", "worldview", new ArrayList<>()),
                   "narrative", new Checkpoint("推文", "narrative", new ArrayList<>())));
        NodeLoader.save(NodeLoader.nodeFile(worldsDir, worldId, "n0000"), root);

        // _index.json
        List<WorldEntry> entries = new ArrayList<>(listWorlds(worldsDir));
        entries.add(new WorldEntry(worldId, name, now));
        saveIndex(worldsDir, entries);

        // active.json
        ActiveStateManager.save(worldsDir, worldId,
            new ActiveStateManager.ActiveState("n0000", Map.of()));

        return meta;
    }
}
