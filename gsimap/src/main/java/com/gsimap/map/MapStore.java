package com.gsimap.map;

import com.gsim.worldinfo.loader.NodeLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Reads and writes map files via GSim Core's {@link NodeLoader} attachment mechanism.
 *
 * <p>Map data is stored as independent attachment files ({@code nXXXX_map.json})
 * managed by {@link NodeLoader#saveAttachmentFile} / {@link NodeLoader#loadAttachmentFile},
 * which also updates the node JSON's attachments map with a light reference.
 *
 * <p>This replaces the old approach of directly reading/writing
 * {@code worlds/{worldId}/nodes/{nodeId}_map.json} in MapStore.
 */
public final class MapStore {

    private static final Logger log = LoggerFactory.getLogger(MapStore.class);

    private MapStore() {}

    // ── Path helpers ─────────────────────────────────────

    /**
     * Returns the attachment file path for a node's map.
     * Delegates to {@link NodeLoader#attachmentFilePath}.
     */
    public static Path mapFile(Path worldsDir, String worldId, String nodeId) {
        return NodeLoader.attachmentFilePath(worldsDir, worldId, nodeId, "map");
    }

    /**
     * Check if a map attachment exists for the given node.
     */
    public static boolean exists(Path worldsDir, String worldId, String nodeId) {
        return Files.exists(mapFile(worldsDir, worldId, nodeId));
    }

    // ── Full map ─────────────────────────────────────────

    /**
     * Load a full MapData via NodeLoader attachment.
     */
    public static MapData loadFull(Path worldsDir, String worldId, String nodeId) {
        return NodeLoader.loadAttachmentFile(worldsDir, worldId, nodeId, "map", MapData.class);
    }

    /**
     * Save a full MapData via NodeLoader attachment.
     */
    public static void saveFull(Path worldsDir, String worldId, String nodeId, MapData data) {
        if (data == null) throw new MapStoreException("data must not be null");
        NodeLoader.saveAttachmentFile(worldsDir, worldId, nodeId, "map", data);
        log.debug("Saved full map via attachment: {}/{}:{}", worldId, nodeId, "map");
    }

    // ── Diff map ─────────────────────────────────────────

    /**
     * Load a MapDiff via NodeLoader attachment.
     */
    public static MapDiff loadDiff(Path worldsDir, String worldId, String nodeId) {
        return NodeLoader.loadAttachmentFile(worldsDir, worldId, nodeId, "map_diff", MapDiff.class);
    }

    /**
     * Save a MapDiff via NodeLoader attachment.
     */
    public static void saveDiff(Path worldsDir, String worldId, String nodeId, MapDiff diff) {
        if (diff == null) throw new MapStoreException("diff must not be null");
        NodeLoader.saveAttachmentFile(worldsDir, worldId, nodeId, "map_diff", diff);
        log.debug("Saved map diff via attachment: {}/{}:{}", worldId, nodeId, "map_diff");
    }
}
