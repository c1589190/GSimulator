package com.gsim.worldinfo.loader;

import com.gsim.util.JsonUtils;
import com.gsim.worldinfo.NodeSnapshot;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Reads/writes a single node JSON file from worlds/{worldId}/nodes/.
 */
public final class NodeLoader {

    private NodeLoader() {}

    /** Load a single node from its JSON file. */
    public static NodeSnapshot load(Path nodeFile) {
        if (!Files.exists(nodeFile)) {
            throw new IllegalArgumentException("Node file not found: " + nodeFile);
        }
        try {
            String json = Files.readString(nodeFile);
            return JsonUtils.fromJson(json, NodeSnapshot.class);
        } catch (IOException e) {
            throw new RuntimeException("Failed to load node: " + nodeFile, e);
        }
    }

    /** Save a node to its JSON file. Creates parent directories if needed. */
    public static void save(Path nodeFile, NodeSnapshot node) {
        try {
            Files.createDirectories(nodeFile.getParent());
            String json = JsonUtils.toJson(node);
            Files.writeString(nodeFile, json);
        } catch (IOException e) {
            throw new RuntimeException("Failed to save node: " + nodeFile, e);
        }
    }

    /** The nodes directory for a given world. */
    public static Path nodesDir(Path worldsDir, String worldId) {
        return worldsDir.resolve(worldId).resolve("nodes");
    }

    /** Path to a specific node JSON file. */
    public static Path nodeFile(Path worldsDir, String worldId, String nodeId) {
        return nodesDir(worldsDir, worldId).resolve(nodeId + ".json");
    }
}
