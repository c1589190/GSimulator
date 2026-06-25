package com.gsim.worldinfo.loader;

import com.gsim.worldinfo.NodeSnapshot;
import com.gsim.worldinfo.WorldInformation;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Builds a WorldInformation by walking the parent chain from the active node.
 */
public final class WorldInfoBuilder {

    private WorldInfoBuilder() {}

    /**
     * Walk parentId chain from activeNodeId to root, load all nodes, build WorldInformation.
     * Returns null if the world has no nodes directory.
     */
    public static WorldInformation build(Path worldsDir, String worldId, String activeNodeId) {
        Path nodesDir = NodeLoader.nodesDir(worldsDir, worldId);
        if (!Files.exists(nodesDir)) {
            return null;
        }

        // 1. Load active node
        NodeSnapshot current = NodeLoader.load(NodeLoader.nodeFile(worldsDir, worldId, activeNodeId));

        // 2. Walk up parent chain
        List<NodeSnapshot> chain = new ArrayList<>();
        chain.add(current);
        NodeSnapshot cursor = current;
        while (!cursor.isRoot()) {
            Path parentFile = NodeLoader.nodeFile(worldsDir, worldId, cursor.parentId());
            if (!Files.exists(parentFile)) break; // safety: broken chain
            cursor = NodeLoader.load(parentFile);
            chain.add(cursor);
        }

        // 3. Reverse so root is first
        java.util.Collections.reverse(chain);

        // 4. Build
        return new WorldInformation(worldId, chain);
    }
}
