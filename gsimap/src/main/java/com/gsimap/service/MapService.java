package com.gsimap.service;

import com.gsimap.map.MapData;
import com.gsimap.map.MapDiff;
import com.gsimap.map.MapResolver;
import com.gsimap.map.MapStore;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Core map service — resolves map data from the GSim worlds directory,
 * applies diffs, manages an in-memory LRU cache.
 */
public class MapService {

    private static final Logger log = LoggerFactory.getLogger(MapService.class);
    private static final int MAX_CACHE_SIZE = 32;

    private final Path worldsDir;
    private final Map<String, MapData> cache = Collections.synchronizedMap(new LruCache());

    private static final class LruCache extends LinkedHashMap<String, MapData> {
        private static final long serialVersionUID = 1L;

        @Override
        protected boolean removeEldestEntry(Map.Entry<String, MapData> eldest) {
            return size() > MAX_CACHE_SIZE;
        }
    }

    private final ConcurrentHashMap<String, TerrainCanvas> canvases = new ConcurrentHashMap<>();

    private static String canvasKey(String worldId, String nodeId) {
        return worldId + ":" + nodeId;
    }

    /**
     * Creates a new MapService for the given worlds directory.
     * @param worldsDir path to the worlds directory
     */
    public MapService(Path worldsDir) {
        this.worldsDir = worldsDir;
        if (!Files.isDirectory(worldsDir)) {
            log.warn("Worlds directory does not exist: {}", worldsDir);
        }
    }

    /**
     * Returns the worlds directory path used by this service.
     * @return the worlds directory path used by this service
     */
    public Path getWorldsDir() {
        return worldsDir;
    }

    // ── Query ────────────────────────────────────────────

    /**
     * Resolve the full map for a given world and node.
     * @param worldId the world identifier
     * @param nodeId the node identifier
     * @return the resolved map data
     */
    public MapData resolve(String worldId, String nodeId) {
        String key = cacheKey(worldId, nodeId);
        MapData cached = cache.get(key);
        if (cached != null) return cached;
        MapData loaded = loadMapData(worldId, nodeId);
        if (loaded != null) cache.put(key, loaded);
        return loaded;
    }

    /** Load MapData with CR validation and repair on every load. */
    private MapData loadMapData(String worldId, String nodeId) {
        MapData map;
        if (isRootNode(worldId, nodeId)) {
            map = MapStore.loadFull(worldsDir, worldId, nodeId);
        } else {
            // Child node: use MapResolver for diff chain
            map = MapResolver.resolve(worldsDir, worldId, nodeId);
        }
        // Transparently validate and repair CR boundaries on every load.
        // This fixes legacy single-ring boundaries that lack hole rings.
        if (map != null && !map.compressedRegions().isEmpty()) {
            map = CompressionValidator.validateAndRepair(map);
        }
        return map;
    }

    public boolean isRootNode(String worldId, String nodeId) {
        Path nodeFile = com.gsim.worldinfo.loader.NodeLoader.nodeFile(worldsDir, worldId, nodeId);
        if (!Files.exists(nodeFile)) {
            // File truly absent (first access, node not yet created) → treat as root
            return true;
        }
        // File exists — try to parse it
        try {
            return com.gsim.worldinfo.loader.NodeLoader.load(nodeFile).isRoot();
        } catch (RuntimeException e) {
            // Corrupt node file (e.g. 0 bytes from failed write) — log and treat as non-root
            log.warn("Cannot determine if {} is root node (file may be corrupt): {}", nodeId, e.getMessage());
            return false;
        }
    }

    /**
     * Get map for the active node of a world.
     * @param worldId the world identifier
     * @return the resolved map data for the active node
     */
    public MapData resolveActive(String worldId) {
        String nodeId = readActiveNodeId(worldId);
        return resolve(worldId, nodeId);
    }

    /**
     * Get history for a world and node.
     * @param worldId the world identifier
     * @param nodeId the node identifier
     * @return list of history entries
     */
    public List<MapResolver.HistoryEntry> history(String worldId, String nodeId) {
        return MapResolver.history(worldsDir, worldId, nodeId);
    }

    // ── TerrainCanvas (block system) ────────────────────────

    /**
     * Get or lazily create the TerrainCanvas for a world + node.
     * Cache key includes nodeId to prevent cross-branch canvas leakage.
     * @param worldId the world identifier
     * @param nodeId the node identifier
     * @return the terrain canvas for the given world and node
     */
    public TerrainCanvas getCanvas(String worldId, String nodeId) {
        String key = canvasKey(worldId, nodeId);
        return canvases.computeIfAbsent(key, k -> {
            MapData map = resolve(worldId, nodeId);
            TerrainCanvas canvas = new TerrainCanvas();
            if (map != null
                    && map.terrainBlocks() != null
                    && !map.terrainBlocks().isEmpty()) {
                canvas.setBlocks(map.terrainBlocks());
                log.info("Loaded {} blocks for {}:{}", canvas.size(), worldId, nodeId);
            } else {
                log.info("Empty canvas for {}:{}", worldId, nodeId);
            }
            return canvas;
        });
    }

    /**
     * Query terrain for a hex using TerrainCanvas (primary), falling back to hex grid.
     * @param worldId the world identifier
     * @param q hex axial q coordinate
     * @param r hex axial r coordinate
     * @return terrain type string, or null if not found
     */
    public String queryTerrainBlock(String worldId, int q, int r) {
        String nodeId = readActiveNodeId(worldId);
        String key = canvasKey(worldId, nodeId);
        TerrainCanvas canvas = canvases.get(key);
        if (canvas != null) {
            String terrain = canvas.queryHex(q, r);
            if (terrain != null) return terrain;
        }
        // Fallback 1: load stored terrainBlocks into canvas and query
        MapData map = resolve(worldId, nodeId);
        if (map != null && map.terrainBlocks() != null && !map.terrainBlocks().isEmpty()) {
            canvas = new TerrainCanvas();
            canvas.setBlocks(map.terrainBlocks());
            canvases.put(key, canvas);
            String terrain = canvas.queryHex(q, r);
            if (terrain != null) return terrain;
        }
        // Fallback 2: query hex grid
        if (map != null) {
            MapData.HexCell cell = map.hexes().get(MapData.hexKey(q, r));
            if (cell != null) return cell.terrain();
        }
        return null;
    }

    /**
     * Add a terrain block to a world's canvas and persist.
     * @param worldId the world identifier
     * @param terrain the terrain type
     * @param boundary the block boundary points
     * @param seedKey optional seed key for the block
     * @return the block ID, or null if creation failed
     */
    public String addBlock(String worldId, String terrain, List<MapData.Pt> boundary, String seedKey) {
        String nodeId = readActiveNodeId(worldId);
        TerrainCanvas canvas = getCanvas(worldId, nodeId);
        String blockId = canvas.addBlock(terrain, boundary, seedKey);
        if (blockId != null) {
            persistBlocks(worldId, canvas);
        }
        return blockId;
    }

    /**
     * Add a block from pre-computed hex set (client-side flood fill).
     * @param worldId the world identifier
     * @param terrain the terrain type
     * @param hexSet the set of hex keys defining the block
     * @param seedKey optional seed key for the block
     * @return the block ID, or null if creation failed
     */
    public String addBlockFromHexSet(String worldId, String terrain, Set<String> hexSet, String seedKey) {
        String nodeId = readActiveNodeId(worldId);
        TerrainCanvas canvas = getCanvas(worldId, nodeId);
        String blockId = canvas.addBlockFromHexSet(terrain, hexSet, seedKey);
        if (blockId != null) {
            persistBlocks(worldId, canvas);
        }
        return blockId;
    }

    /**
     * Remove a terrain block by id and persist.
     * @param worldId the world identifier
     * @param blockId the block identifier to remove
     * @return true if the block was found and removed
     */
    public boolean removeBlock(String worldId, String blockId) {
        String nodeId = readActiveNodeId(worldId);
        TerrainCanvas canvas = canvases.get(canvasKey(worldId, nodeId));
        if (canvas == null) return false;
        boolean ok = canvas.removeBlock(blockId);
        if (ok) persistBlocks(worldId, canvas);
        return ok;
    }

    /**
     * Evict the in-memory canvas for a world+node, forcing reload on next access.
     * @param worldId the world identifier
     * @param nodeId the node identifier
     */
    public void evictCanvas(String worldId, String nodeId) {
        canvases.remove(canvasKey(worldId, nodeId));
    }

    /** Write terrain blocks back to MapData and persist (does NOT evict canvas).
     *  Terrain blocks are a world-level concept — saved to the active node and
     *  inherited by child nodes through the diff chain.
     *  rivers/roads: 已废弃，使用 List.of() 占位，将在 PathwayGroup 连通性系统中重建。 */
    private void persistBlocks(String worldId, TerrainCanvas canvas) {
        List<MapData.TerrainBlock> blocks = canvas.getBlocks();
        String activeNodeId = readActiveNodeId(worldId);
        MapData map = resolveActive(worldId);
        if (map == null || map.hexes().isEmpty()) {
            map = MapData.empty();
        }
        MapData updated = new MapData(
                map.gridSize(),
                map.hexOrientation(),
                map.hexes(),
                blocks,
                map.provinces(),
                map.cities(),
                List.of(), // rivers: 已废弃，将由 PathwayGroup 连通性系统替代
                List.of(), // roads:  已废弃，将由 PathwayGroup 连通性系统替代
                map.terrainTypes(),
                map.compressedRegions(),
                map.pathwayGroups(),
                Map.of());
        // Save to the active node (typically root, but respects current active node).
        // saveMap() already calls evict(), so no need to duplicate here.
        saveMap(worldId, activeNodeId, updated);
    }

    // ── Mutation ──────────────────────────────────────────

    /**
     * Save a map — automatically chooses full (root) or diff (child).
     * This is the ONLY save entry point. Business methods MUST NOT call
     * MapStore.saveFull/saveDiff directly.
     */
    public void saveMap(String worldId, String nodeId, MapData updated) {
        if (isRootNode(worldId, nodeId)) {
            MapStore.saveFull(worldsDir, worldId, nodeId, updated);
        } else {
            MapData parent = resolve(worldId, readParentId(worldId, nodeId));
            MapDiff diff = MapDiff.compute(readParentId(worldId, nodeId), parent, updated);
            MapStore.saveDiff(worldsDir, worldId, nodeId, diff);
        }
        evict(worldId, nodeId);
    }

    // ── Edge operations ────────────────────────────────────

    /**
     * Set a pathway tag on the edge between two hexes.
     * Creates the edge record if it doesn't exist; merges/overwrites tag properties.
     * rivers/roads pass-through is suppressed: deprecated, carried for backward compat.
     */
    @SuppressWarnings("deprecation")
    public MapData setEdgeTag(
            String worldId, String nodeId, int q1, int r1, int q2, int r2, String tag, Map<String, Object> props) {
        MapData map = resolveActive(worldId);
        if (map == null) throw new IllegalArgumentException("No map data for world: " + worldId);

        if (!map.pathwayGroups().containsKey(tag)) {
            throw new IllegalArgumentException("Unknown pathway group: " + tag + ". Available: "
                    + map.pathwayGroups().keySet());
        }

        String key = MapData.edgeKey(q1, r1, q2, r2);
        Map<String, Map<String, Map<String, Object>>> edges = new LinkedHashMap<>(map.edges());
        Map<String, Map<String, Object>> edge = edges.computeIfAbsent(key, k -> new LinkedHashMap<>());
        edge.put(tag, Map.copyOf(props));
        edges.put(key, Map.copyOf(edge));

        MapData updated = new MapData(
                map.gridSize(),
                map.hexOrientation(),
                map.hexes(),
                map.terrainBlocks(),
                map.provinces(),
                map.cities(),
                map.rivers(),
                map.roads(),
                map.terrainTypes(),
                map.compressedRegions(),
                map.pathwayGroups(),
                Map.copyOf(edges));
        saveMap(worldId, nodeId, updated);
        return updated;
    }

    /**
     * Remove a pathway tag from the edge between two hexes.
     * If no tags remain on the edge, the edge record is deleted.
     * rivers/roads pass-through is suppressed: deprecated, carried for backward compat.
     */
    @SuppressWarnings("deprecation")
    public MapData removeEdgeTag(String worldId, String nodeId, int q1, int r1, int q2, int r2, String tag) {
        MapData map = resolveActive(worldId);
        if (map == null) throw new IllegalArgumentException("No map data for world: " + worldId);

        String key = MapData.edgeKey(q1, r1, q2, r2);
        Map<String, Map<String, Map<String, Object>>> edges = new LinkedHashMap<>(map.edges());

        Map<String, Map<String, Object>> edge = edges.get(key);
        if (edge == null) {
            throw new IllegalArgumentException("Edge not found: " + key);
        }
        if (!edge.containsKey(tag)) {
            throw new IllegalArgumentException("Tag '" + tag + "' not found on edge " + key);
        }

        Map<String, Map<String, Object>> newEdge = new LinkedHashMap<>(edge);
        newEdge.remove(tag);
        if (newEdge.isEmpty()) {
            edges.remove(key);
        } else {
            edges.put(key, Map.copyOf(newEdge));
        }

        MapData updated = new MapData(
                map.gridSize(),
                map.hexOrientation(),
                map.hexes(),
                map.terrainBlocks(),
                map.provinces(),
                map.cities(),
                map.rivers(),
                map.roads(),
                map.terrainTypes(),
                map.compressedRegions(),
                map.pathwayGroups(),
                Map.copyOf(edges));
        saveMap(worldId, nodeId, updated);
        return updated;
    }

    /**
     * Merge edge tag values with defaults defined in PathwayGroup.properties.
     */
    public static Map<String, Map<String, Object>> resolveEdgeWithDefaults(
            Map<String, Map<String, Object>> raw, Map<String, MapData.PathwayGroup> groups) {
        if (raw == null) return null;
        Map<String, Map<String, Object>> resolved = new LinkedHashMap<>();
        for (var entry : raw.entrySet()) {
            String pathwayId = entry.getKey();
            Map<String, Object> values = new LinkedHashMap<>(entry.getValue());
            MapData.PathwayGroup group = groups.get(pathwayId);
            if (group != null) {
                for (var prop : group.properties().entrySet()) {
                    if (!values.containsKey(prop.getKey())) {
                        values.put(prop.getKey(), prop.getValue().defaultValue());
                    }
                }
            }
            resolved.put(pathwayId, Map.copyOf(values));
        }
        return Map.copyOf(resolved);
    }

    /**
     * Update pathway groups for a world — replaces the entire groups map and persists.
     */
    public void updatePathwayGroups(String worldId, Map<String, MapData.PathwayGroup> groups) {
        String nodeId = readActiveNodeId(worldId);
        MapData map = resolveActive(worldId);
        if (map == null || map.hexes().isEmpty()) {
            map = MapData.empty();
        }
        MapData updated = new MapData(
                map.gridSize(),
                map.hexOrientation(),
                map.hexes(),
                map.terrainBlocks(),
                map.provinces(),
                map.cities(),
                List.of(), // rivers: 已废弃，将由 PathwayGroup 连通性系统替代
                List.of(), // roads:  已废弃，将由 PathwayGroup 连通性系统替代
                map.terrainTypes(),
                map.compressedRegions(),
                groups,
                map.edges());
        saveMap(worldId, nodeId, updated);
    }

    /**
     * Check if a world exists by verifying its world.json file.
     * @param worldId the world identifier
     * @return true if the world.json exists
     */
    public boolean worldExists(String worldId) {
        return Files.exists(worldsDir.resolve(worldId).resolve("world.json"));
    }

    /**
     * List world identifiers that have map data in the worlds directory.
     * @return list of world IDs with at least one _map.json node
     */
    public List<String> listWorldsWithMaps() {
        List<String> result = new ArrayList<>();
        java.io.File[] worldDirs = worldsDir.toFile().listFiles(java.io.File::isDirectory);
        if (worldDirs == null) return result;
        for (java.io.File wf : worldDirs) {
            Path w = wf.toPath();
            Path nodesDir = w.resolve("nodes");
            if (!Files.isDirectory(nodesDir)) continue;
            // Check if any node has a _map.json
            try (var stream = Files.list(nodesDir)) {
                if (stream.anyMatch(f -> {
                    Path fn = f.getFileName();
                    return fn != null && fn.toString().endsWith("_map.json");
                })) {
                    Path fileName = w.getFileName();
                    if (fileName != null) {
                        result.add(fileName.toString());
                    }
                }
            } catch (IOException ignored) {
            }
        }
        return result;
    }

    // ── Nodes ──────────────────────────────────────────────

    /**
     * List all nodes in a world directory.
     * @param worldId the world identifier
     * @return list of node info maps, each containing nodeId, turn, worldTime, and hasMap
     */
    public List<Map<String, Object>> listNodes(String worldId) {
        List<Map<String, Object>> result = new ArrayList<>();
        Path nodesDir = worldsDir.resolve(worldId).resolve("nodes");
        if (!Files.isDirectory(nodesDir)) return result;

        try (var stream = Files.list(nodesDir)) {
            stream.filter(f -> {
                        java.nio.file.Path fn = f.getFileName();
                        if (fn == null) return false;
                        String name = fn.toString();
                        // 只匹配精确的 nXXXX.json 节点文件，排除 map/attachment/contour 等
                        return name.matches("n\\d{4}\\.json");
                    })
                    .sorted()
                    .forEach(f -> {
                        try {
                            var node = com.gsim.worldinfo.loader.NodeLoader.load(f);
                            Map<String, Object> info = new LinkedHashMap<>();
                            java.nio.file.Path fn = f.getFileName();
                            if (fn == null) return;
                            String nid = fn.toString().replace(".json", "");
                            info.put("nodeId", nid);
                            info.put("turn", node.turn());
                            info.put("worldTime", node.worldTime());
                            info.put(
                                    "hasMap",
                                    Files.exists(com.gsim.worldinfo.loader.NodeLoader.attachmentFilePath(
                                            worldsDir, worldId, nid, "map")));
                            result.add(info);
                        } catch (IllegalArgumentException ignored) {
                        }
                    });
        } catch (IOException ignored) {
        }
        return result;
    }

    // ── Pathfinding ────────────────────────────────────────

    /**
     * Find the minimum-cost path from a source hex to the nearest water hex (or map edge).
     * Uses Dijkstra with terrain moveCost as edge weight.
     * Falls back to A* with hex-distance heuristic to water.
     * @param worldId the world identifier
     * @param nodeId the node identifier
     * @param fromQ starting hex axial q coordinate
     * @param fromR starting hex axial r coordinate
     * @return list of hex keys forming the path to the nearest water hex, or empty list if none found
     */
    public List<String> findRiverPath(String worldId, String nodeId, int fromQ, int fromR) {
        MapData map = resolve(worldId, nodeId != null ? nodeId : "n0000");
        String startKey = MapData.hexKey(fromQ, fromR);
        log.info(
                "findRiverPath: world={} node={} start={} hexes={}",
                worldId,
                nodeId,
                startKey,
                map.hexes().size());

        // Priority queue: [fCost, gCost, q, r]
        var pq = new java.util.PriorityQueue<int[]>(java.util.Comparator.comparingInt(a -> a[0]));
        var cameFrom = new java.util.HashMap<String, String>();
        var gCost = new java.util.HashMap<String, Integer>();
        gCost.put(startKey, 0);

        // A* heuristic: estimated distance to nearest water
        int h = estimateWaterDistance(map, fromQ, fromR);
        pq.add(new int[] {h, 0, fromQ, fromR});

        int[][] dirs = {{1, 0}, {1, -1}, {0, -1}, {-1, 0}, {-1, 1}, {0, 1}};
        String targetKey = null;
        int iter = 0;
        int maxIter = 5000;

        while (!pq.isEmpty() && iter++ < maxIter) {
            int[] cur = pq.poll();
            int g = cur[1];
            int q = cur[2];
            int r = cur[3];
            String curKey = MapData.hexKey(q, r);

            if (g > gCost.getOrDefault(curKey, Integer.MAX_VALUE)) continue;

            // Check if this is water (goal)
            MapData.HexCell cell = map.hexes().get(curKey);
            if (cell != null && "water".equals(cell.terrain())) {
                targetKey = curKey;
                break;
            }

            for (int[] d : dirs) {
                int nq = q + d[0];
                int nr = r + d[1];
                String nk = MapData.hexKey(nq, nr);
                MapData.HexCell nc = map.hexes().get(nk);
                if (nc == null) continue; // don't expand into void
                int moveCost = map.terrainTypes().containsKey(nc.terrain())
                        ? map.terrainTypes().get(nc.terrain()).moveCost()
                        : 2;
                int ng = g + moveCost;
                if (ng < gCost.getOrDefault(nk, Integer.MAX_VALUE)) {
                    gCost.put(nk, ng);
                    cameFrom.put(nk, curKey);
                    int nh = estimateWaterDistance(map, nq, nr);
                    pq.add(new int[] {ng + nh, ng, nq, nr});
                }
            }
        }

        if (targetKey == null) {
            log.warn("findRiverPath: no path found from {}", startKey);
            return List.of();
        }

        log.info("findRiverPath: found target={} after {} iters", targetKey, iter);

        // Reconstruct path
        var path = new java.util.ArrayList<String>();
        String k = targetKey;
        while (k != null) {
            path.add(k);
            k = cameFrom.get(k);
        }
        java.util.Collections.reverse(path);
        return path;
    }

    private int estimateWaterDistance(MapData map, int q, int r) {
        // Spiral ring search for nearest water hex (capped at 20)
        int maxR = 20;
        int[][] dirs = {{1, 0}, {1, -1}, {0, -1}, {-1, 0}, {-1, 1}, {0, 1}};
        for (int radius = 1; radius <= maxR; radius++) {
            // Start at NW corner of the ring (direction 4)
            int cq = q + dirs[4][0] * radius;
            int cr = r + dirs[4][1] * radius;
            for (int d = 0; d < 6; d++) {
                for (int step = 0; step < radius; step++) {
                    String key = MapData.hexKey(cq, cr);
                    MapData.HexCell cell = map.hexes().get(key);
                    if (cell == null) return radius;
                    if ("water".equals(cell.terrain())) return radius;
                    cq += dirs[d][0];
                    cr += dirs[d][1];
                }
            }
        }
        return maxR;
    }

    // ── Contour ────────────────────────────────────────────

    /**
     * Save continent contour for a world.
     * @param worldId the world identifier
     * @param contour the continent contour to save
     */
    public void saveContour(String worldId, ContinentContour contour) {
        com.gsim.worldinfo.loader.NodeLoader.saveAttachmentFile(worldsDir, worldId, "n0000", "contour", contour);
        evict(worldId, "n0000");
    }

    /**
     * Load continent contour for a world.
     * @param worldId the world identifier
     * @return the loaded continent contour, or null if not found
     */
    public ContinentContour loadContour(String worldId) {
        return com.gsim.worldinfo.loader.NodeLoader.loadAttachmentFile(
                worldsDir, worldId, "n0000", "contour", ContinentContour.class);
    }

    /**
     * Query terrain for a single hex using contour (lazy, cached).
     * @param worldId the world identifier
     * @param q hex axial q coordinate
     * @param r hex axial r coordinate
     * @return terrain sample for the given hex
     */
    public ContourQueryEngine.TerrainSample queryTerrain(String worldId, int q, int r) {
        ContinentContour contour = loadContour(worldId);
        if (contour == null) {
            // Fallback: resolve full map and query traditionally
            MapData map = resolve(worldId, null);
            MapData.HexCell cell = map.hexes().get(q + "_" + r);
            if (cell == null) return new ContourQueryEngine.TerrainSample(0, "water", "#3295D2");
            return new ContourQueryEngine.TerrainSample(0.5, cell.terrain(), cell.color());
        }
        ContourQueryEngine engine = new ContourQueryEngine(contour);
        return engine.query(q, r);
    }

    // ── Cache ─────────────────────────────────────────────

    // ── Region Rename ──────────────────────────────────────

    /**
     * Rename a region across all data stores: MapData + GSim node checkpoints.
     * @param worldId the world identifier
     * @param nodeId the node identifier
     * @param oldName the current region name
     * @param newName the desired new region name
     * @return a map with ok status and error message if failed
     */
    public Map<String, Object> renameRegion(String worldId, String nodeId, String oldName, String newName) {
        if (oldName == null || newName == null || oldName.equals(newName))
            return Map.of("ok", false, "error", "Invalid names");
        if (newName.isBlank()) return Map.of("ok", false, "error", "New name must not be blank");

        MapData map = resolve(worldId, nodeId);
        if (map == null || !map.provinces().containsKey(oldName))
            return Map.of("ok", false, "error", "Region not found: " + oldName);
        if (map.provinces().containsKey(newName))
            return Map.of("ok", false, "error", "Region already exists: " + newName);

        // 1. Rename province in MapData
        Map<String, MapData.Province> updated = new LinkedHashMap<>();
        for (var e : map.provinces().entrySet()) {
            if (e.getKey().equals(oldName)) {
                updated.put(newName, e.getValue());
            } else {
                updated.put(e.getKey(), e.getValue());
            }
        }
        MapData newMap = new MapData(
                map.gridSize(),
                map.hexOrientation(),
                map.hexes(),
                map.terrainBlocks(),
                updated,
                map.cities(),
                List.of(), // rivers: 已废弃，将由 PathwayGroup 连通性系统替代
                List.of(), // roads:  已废弃，将由 PathwayGroup 连通性系统替代
                map.terrainTypes(),
                map.compressedRegions(),
                map.pathwayGroups(),
                Map.of());
        saveMap(worldId, nodeId, newMap);

        // 2. Re-sync map (checkpoint references managed by GSim Core, not gsimap)
        evict(worldId, nodeId); // ensure HTTP cache sees MCP writes

        log.info("Renamed region '{}' -> '{}' in world={} node={}", oldName, newName, worldId, nodeId);
        return resultMap("ok", true, "oldName", oldName, "newName", newName);
    }

    // ── Map Expansion ──────────────────────────────────────

    /** Hex neighbor directions in axial coordinates (q, r). */
    static final int[][] HEX_DIRS = {{1, 0}, {1, -1}, {0, -1}, {-1, 0}, {-1, 1}, {0, 1}};

    private static final String[] EXPAND_NAMES = {"E", "NE", "NW", "W", "SW", "SE"};

    // ── Geometry helpers (public, shared across gsimap) ──

    /** Hex distance in axial coordinates (standard hex grid metric). */
    public static int hexDistance(int q1, int r1, int q2, int r2) {
        return (Math.abs(q1 - q2) + Math.abs(r1 - r2) + Math.abs((-q1 - r1) - (-q2 - r2))) / 2;
    }

    /** Compute the center of mass of a list of hex keys, returned as {q, r}. */
    public static int[] computeCenter(List<String> hexes) {
        if (hexes == null || hexes.isEmpty()) return new int[] {0, 0};
        int sq = 0, sr = 0;
        for (String hk : hexes) {
            int[] qr = MapData.parseHexKey(hk);
            sq += qr[0];
            sr += qr[1];
        }
        return new int[] {Math.round((float) sq / hexes.size()), Math.round((float) sr / hexes.size())};
    }

    /** Count terrain types within a province's hexes. */
    public static Map<String, Integer> computeTerrainComposition(MapData.Province prov, MapData map) {
        Map<String, Integer> comp = new LinkedHashMap<>();
        if (prov.hexes() == null || map.hexes() == null) return comp;
        for (String hk : prov.hexes()) {
            MapData.HexCell cell = map.hexes().get(hk);
            if (cell != null) comp.merge(cell.terrain(), 1, Integer::sum);
        }
        return comp;
    }

    /** Compute adjacency list — for each other region sharing a hex edge with {@code ownHexes}. */
    public static List<Map<String, Object>> computeAdjacency(
            Set<String> ownHexes, Map<String, Set<String>> allRegionHexes) {
        List<Map<String, Object>> result = new ArrayList<>();
        for (var entry : allRegionHexes.entrySet()) {
            String otherName = entry.getKey();
            Set<String> otherHexes = entry.getValue();
            if (otherHexes == ownHexes) continue;
            int sharedEdges = 0;
            for (String hk : ownHexes) {
                int[] qr = MapData.parseHexKey(hk);
                for (int[] d : HEX_DIRS) {
                    if (otherHexes.contains(MapData.hexKey(qr[0] + d[0], qr[1] + d[1]))) sharedEdges++;
                }
            }
            if (sharedEdges > 0) result.add(Map.of("name", otherName, "sharedEdges", sharedEdges));
        }
        return result;
    }

    /**
     * Expand the map by attaching a same-size hexagon in the given direction,
     * then filling diamond-feet gaps to form a larger coherent hexagon.
     * @param worldId the world identifier
     * @param nodeId the node identifier
     * @param direction the direction to expand (E, NE, NW, W, SW, SE)
     * @param attachRadius the radius for the attached hexagon
     * @return a result map with ok status and expansion details
     */
    public Map<String, Object> expand(String worldId, String nodeId, String direction, int attachRadius) {
        MapData map = resolve(worldId, nodeId);
        if (map == null || map.hexes().isEmpty()) return Map.of("ok", false, "error", "No map data");

        // Find direction index
        int dirIdx = -1;
        for (int i = 0; i < EXPAND_NAMES.length; i++) {
            if (EXPAND_NAMES[i].equals(direction)) {
                dirIdx = i;
                break;
            }
        }
        if (dirIdx < 0)
            return Map.of("ok", false, "error", "Invalid direction: " + direction + ". Use: E, NE, NW, W, SW, SE");

        // Compute current center and radius from hex data
        int minQ = Integer.MAX_VALUE;
        int maxQ = Integer.MIN_VALUE;
        int minR = Integer.MAX_VALUE;
        int maxR = Integer.MIN_VALUE;
        for (String key : map.hexes().keySet()) {
            int[] qr = MapData.parseHexKey(key);
            if (qr[0] < minQ) minQ = qr[0];
            if (qr[0] > maxQ) maxQ = qr[0];
            if (qr[1] < minR) minR = qr[1];
            if (qr[1] > maxR) maxR = qr[1];
        }
        int cq = (minQ + maxQ) / 2;
        int cr = (minR + maxR) / 2;
        int cs = -cq - cr;
        int radius = 0;
        for (String key : map.hexes().keySet()) {
            int[] qr = MapData.parseHexKey(key);
            int s = -qr[0] - qr[1];
            radius = Math.max(radius, Math.abs(qr[0] - cq) + Math.abs(qr[1] - cr) + Math.abs(s - cs));
        }
        radius = (radius + 1) / 2;
        int useRadius = attachRadius > 0 ? attachRadius : radius;

        int[] dir = HEX_DIRS[dirIdx];
        int[] perp = HEX_DIRS[(dirIdx + 2) % 6]; // 60°×2 ≈ perpendicular
        int dq = dir[0];
        int dr = dir[1];
        int pq = perp[0];
        int pr = perp[1];

        // Combined hexagon
        int newCq = useRadius * (dq + pq);
        int newCr = useRadius * (dr + pr);
        int newRadius = 2 * useRadius;
        int newCs = -newCq - newCr;

        // Load contour for terrain generation
        ContinentContour contour = loadContour(worldId);
        ContourQueryEngine engine = contour != null ? new ContourQueryEngine(contour) : null;

        // Build expanded hex grid
        var newHexes = new LinkedHashMap<>(map.hexes());
        int added = 0;
        int waterAdded = 0;
        int landAdded = 0;

        for (int q = newCq - newRadius; q <= newCq + newRadius; q++) {
            for (int r = newCr - newRadius; r <= newCr + newRadius; r++) {
                String key = MapData.hexKey(q, r);
                if (newHexes.containsKey(key)) continue;
                int s = -q - r;
                if (Math.abs(q - newCq) + Math.abs(r - newCr) + Math.abs(s - newCs) > 2 * newRadius) continue;

                String terrain;
                String color;
                if (engine != null) {
                    var sample = engine.query(q, r);
                    terrain = sample.terrain();
                    color = sample.color();
                } else {
                    terrain = "lowland";
                    color = "#5B8C3E";
                }
                int riverMask = 0;
                newHexes.put(key, new MapData.HexCell(color, terrain, null, null, "", riverMask, Map.of()));
                added++;
                if ("water".equals(terrain)) {
                    waterAdded++;
                } else {
                    landAdded++;
                }
            }
        }

        int hexesBefore = map.hexes().size();
        MapData expanded = new MapData(
                map.gridSize(),
                map.hexOrientation(),
                newHexes,
                map.terrainBlocks(),
                map.provinces(),
                map.cities(),
                List.of(), // rivers: 已废弃，将由 PathwayGroup 连通性系统替代
                List.of(), // roads:  已废弃，将由 PathwayGroup 连通性系统替代
                map.terrainTypes(),
                map.compressedRegions(),
                map.pathwayGroups(),
                Map.of());
        saveMap(worldId, nodeId, expanded);

        log.info(
                "Expanded {} → {} ({} new hexes: {} land + {} water), new center=({},{}), radius={}",
                direction,
                worldId,
                added,
                landAdded,
                waterAdded,
                newCq,
                newCr,
                newRadius);

        var result = new LinkedHashMap<String, Object>();
        result.put("ok", true);
        result.put("direction", direction);
        result.put("hexesBefore", hexesBefore);
        result.put("hexesAfter", newHexes.size());
        result.put("added", added);
        result.put("landAdded", landAdded);
        result.put("waterAdded", waterAdded);
        result.put("oldCenter", Map.of("q", cq, "r", cr));
        result.put("oldRadius", useRadius);
        result.put("newCenter", Map.of("q", newCq, "r", newCr));
        result.put("newRadius", newRadius);
        return result;
    }

    // ── Compression ───────────────────────────────────────

    /**
     * Compress resolved map and store in node's map file via saveFull/saveDiff.
     * @param worldId the world identifier
     * @param nodeId the node identifier
     * @param minRegionSize minimum region size for compression
     * @return a result map with ok status and compression details
     */
    public Map<String, Object> compress(String worldId, String nodeId, int minRegionSize) {
        MapData map = resolve(worldId, nodeId);
        if (map == null || map.hexes().isEmpty()) return Map.of("ok", false, "error", "No map data");

        if (minRegionSize <= 0) minRegionSize = CompressionService.DEFAULT_MIN_REGION_SIZE;

        List<MapData.CompressedRegion> regions = CompressionService.compress(map, minRegionSize);

        MapData updated = new MapData(
                map.gridSize(),
                map.hexOrientation(),
                map.hexes(),
                map.terrainBlocks(),
                map.provinces(),
                map.cities(),
                List.of(), // rivers: 已废弃，将由 PathwayGroup 连通性系统替代
                List.of(), // roads:  已废弃，将由 PathwayGroup 连通性系统替代
                map.terrainTypes(),
                regions,
                map.pathwayGroups(),
                Map.of());

        saveMap(worldId, nodeId, updated);

        log.info(
                "Compressed {}/{}: {} hexes, {} regions",
                worldId,
                nodeId,
                map.hexes().size(),
                regions.size());

        var result = new LinkedHashMap<String, Object>();
        result.put("ok", true);
        result.put("nodeId", nodeId);
        result.put("hexCount", map.hexes().size());
        result.put("regions", regions.size());
        int compressedHexes =
                regions.stream().mapToInt(MapData.CompressedRegion::size).sum();
        result.put("compressedCount", compressedHexes);
        result.put(
                "compressionRatio",
                map.hexes().size() > 0
                        ? String.format(
                                "%.1f%%", 100.0 * compressedHexes / map.hexes().size())
                        : "0%");
        return result;
    }

    /**
     * Decompress a specific region by id. Loads current CRs from resolved map, removes, saves.
     * @param worldId the world identifier
     * @param nodeId the node identifier
     * @param regionId the region identifier to decompress
     * @return a result map with ok status and restoration details
     */
    public Map<String, Object> decompress(String worldId, String nodeId, String regionId) {
        MapData map = resolve(worldId, nodeId);
        if (map == null || map.hexes().isEmpty()) return Map.of("ok", false, "error", "No map data");

        List<MapData.CompressedRegion> regions = new ArrayList<>(map.compressedRegions());
        int restored = CompressionService.decompress(regions, regionId);
        if (restored == 0) return Map.of("ok", false, "error", "Region not found: " + regionId);

        MapData updated = new MapData(
                map.gridSize(),
                map.hexOrientation(),
                map.hexes(),
                map.terrainBlocks(),
                map.provinces(),
                map.cities(),
                List.of(), // rivers: 已废弃，将由 PathwayGroup 连通性系统替代
                List.of(), // roads:  已废弃，将由 PathwayGroup 连通性系统替代
                map.terrainTypes(),
                regions,
                map.pathwayGroups(),
                Map.of());
        saveMap(worldId, nodeId, updated);
        return resultMap("ok", true, "restored", restored, "regionsRemaining", regions.size());
    }

    /**
     * Decompress the region covering hex (q, r).
     * @param worldId the world identifier
     * @param nodeId the node identifier
     * @param q hex axial q coordinate
     * @param r hex axial r coordinate
     * @return a result map with ok status and restoration details
     */
    public Map<String, Object> decompressAt(String worldId, String nodeId, int q, int r) {
        MapData map = resolve(worldId, nodeId);
        if (map == null || map.hexes().isEmpty())
            return Map.of("ok", false, "error", String.format(NO_MAP_MSG, worldId));
        List<MapData.CompressedRegion> regions = new ArrayList<>(map.compressedRegions());
        if (regions.isEmpty()) return resultMap("ok", true, "note", "no compressed regions", "q", q, "r", r);

        int restored = CompressionService.decompressAt(regions, q, r);
        if (restored == 0) return resultMap("ok", true, "note", "hex not in any compressed region", "q", q, "r", r);

        MapData updated = new MapData(
                map.gridSize(),
                map.hexOrientation(),
                map.hexes(),
                map.terrainBlocks(),
                map.provinces(),
                map.cities(),
                List.of(), // rivers: 已废弃，将由 PathwayGroup 连通性系统替代
                List.of(), // roads:  已废弃，将由 PathwayGroup 连通性系统替代
                map.terrainTypes(),
                regions,
                map.pathwayGroups(),
                Map.of());
        saveMap(worldId, nodeId, updated);
        return resultMap("ok", true, "restored", restored, "regionsRemaining", regions.size(), "q", q, "r", r);
    }

    /**
     * Evict a specific node and all descendants from both the MapData cache and
     * the TerrainCanvas cache.  Canvas keys use {@code worldId:nodeId} format
     * (see {@link #canvasKey}), so we must remove by prefix, not by worldId alone.
     *
     * @param worldId the world identifier
     * @param nodeId the node identifier to evict
     */
    public void evict(String worldId, String nodeId) {
        String key = cacheKey(worldId, nodeId);
        cache.remove(key);
        // Evict all descendant nodes' cached resolved maps (they inherit from this node)
        String cachePrefix = worldId + "/";
        cache.keySet().removeIf(k -> k.startsWith(cachePrefix));
        // Evict all canvases for this world (keys are worldId:nodeId)
        String canvasPrefix = worldId + ":";
        canvases.keySet().removeIf(k -> k.startsWith(canvasPrefix));
    }

    private static String cacheKey(String worldId, String nodeId) {
        return worldId + "/" + nodeId;
    }

    // ── Map Generation ────────────────────────────────────

    /**
     * Generate a full terrain map for a world using MapGenerator.
     * @return result map with ok, worldId, nodeId, hexCount, landHexes, seed
     */
    public Map<String, Object> generate(
            String worldId,
            String nodeId,
            long seed,
            int radius,
            int ridges,
            int fragments,
            double landRatio,
            double coastRoughness) {
        MapData map = MapGenerator.generate(worldId, seed, radius, ridges, fragments, landRatio, coastRoughness);
        saveMap(worldId, nodeId, map);
        long landHexes = map.hexes().values().stream()
                .filter(h -> !"water".equals(h.terrain()))
                .count();
        return resultMap(
                "ok",
                true,
                "worldId",
                worldId,
                "nodeId",
                nodeId,
                "hexCount",
                map.hexes().size(),
                "landHexes",
                landHexes,
                "seed",
                seed);
    }

    // ── Region CRUD ───────────────────────────────────────

    private static final String NO_MAP_MSG = "No map data for world '%s'. Generate a terrain first (gsimap_generate).";

    /** Build a mutable result map from alternating key-value pairs.
     *  Use instead of {@code Map.of()} when callers may add extra keys (e.g. "address"). */
    private static LinkedHashMap<String, Object> resultMap(Object... kvs) {
        LinkedHashMap<String, Object> map = new LinkedHashMap<>();
        for (int i = 0; i < kvs.length; i += 2) {
            map.put((String) kvs[i], kvs[i + 1]);
        }
        return map;
    }

    /** Rebuild MapData with updated provinces, preserving all other fields.
     *  rivers/roads: 已废弃，使用 List.of() 占位，将在 PathwayGroup 连通性系统中重建。 */
    private MapData withProvinces(MapData source, Map<String, MapData.Province> newProvinces) {
        return new MapData(
                source.gridSize(),
                source.hexOrientation(),
                source.hexes(),
                source.terrainBlocks(),
                newProvinces,
                source.cities(),
                List.of(), // rivers: 已废弃，将由 PathwayGroup 连通性系统替代
                List.of(), // roads:  已废弃，将由 PathwayGroup 连通性系统替代
                source.terrainTypes(),
                source.compressedRegions(),
                source.pathwayGroups(),
                source.edges());
    }

    /** Rebuild MapData with updated terrain types.
     *  rivers/roads: 已废弃，使用 List.of() 占位，将在 PathwayGroup 连通性系统中重建。 */
    private MapData withTerrainTypes(MapData source, Map<String, MapData.TerrainType> newTypes) {
        return new MapData(
                source.gridSize(),
                source.hexOrientation(),
                source.hexes(),
                source.terrainBlocks(),
                source.provinces(),
                source.cities(),
                List.of(), // rivers: 已废弃，将由 PathwayGroup 连通性系统替代
                List.of(), // roads:  已废弃，将由 PathwayGroup 连通性系统替代
                newTypes,
                source.compressedRegions(),
                source.pathwayGroups(),
                source.edges());
    }

    /**
     * Update an existing region's properties and/or hex list.
     * @return result map with ok, name, hexCount, tag, description
     */
    public Map<String, Object> updateRegion(
            String worldId,
            String nodeId,
            String name,
            String tag,
            String description,
            String color,
            List<String> hexes) {
        MapData map = resolve(worldId, nodeId);
        if (map == null || map.hexes().isEmpty())
            return Map.of("ok", false, "error", String.format(NO_MAP_MSG, worldId));
        MapData.Province prov = map.provinces().get(name);
        if (prov == null) return Map.of("ok", false, "error", "Region not found: " + name);

        String newTag = tag != null ? tag : prov.tag();
        String newDesc = description != null ? description : prov.description();
        String newColor = color != null ? color : prov.color();
        List<String> newHexes = hexes != null ? new ArrayList<>(hexes) : prov.hexes();

        Map<String, MapData.Province> updated = new LinkedHashMap<>(map.provinces());
        updated.put(name, new MapData.Province(newHexes, newColor, newTag, newDesc, prov.annexedBy()));
        MapData result = withProvinces(map, updated);
        saveMap(worldId, nodeId, result);
        return resultMap("ok", true, "name", name, "hexCount", newHexes.size(), "tag", newTag, "description", newDesc);
    }

    /**
     * Add a single hex to an existing region.
     * @return result map with ok, name, hexCount, added
     */
    public Map<String, Object> addHexToRegion(String worldId, String nodeId, String name, int q, int r) {
        MapData map = resolve(worldId, nodeId);
        if (map == null || map.hexes().isEmpty())
            return Map.of("ok", false, "error", String.format(NO_MAP_MSG, worldId));
        MapData.Province prov = map.provinces().get(name);
        if (prov == null) return Map.of("ok", false, "error", "Region not found: " + name);

        String hexKey = MapData.hexKey(q, r);
        if (!map.hexes().containsKey(hexKey)) return Map.of("ok", false, "error", "Hex not on map: " + hexKey);

        List<String> newHexes = new ArrayList<>(prov.hexes());
        if (newHexes.contains(hexKey))
            return resultMap("ok", true, "name", name, "hexCount", newHexes.size(), "note", "hex already in region");

        newHexes.add(hexKey);
        Map<String, MapData.Province> updated = new LinkedHashMap<>(map.provinces());
        updated.put(
                name, new MapData.Province(newHexes, prov.color(), prov.tag(), prov.description(), prov.annexedBy()));
        MapData result = withProvinces(map, updated);
        saveMap(worldId, nodeId, result);
        return resultMap("ok", true, "name", name, "hexCount", newHexes.size(), "added", hexKey);
    }

    /**
     * Remove a single hex from an existing region.
     * @return result map with ok, name, hexCount, removed
     */
    public Map<String, Object> removeHexFromRegion(String worldId, String nodeId, String name, int q, int r) {
        MapData map = resolve(worldId, nodeId);
        if (map == null || map.hexes().isEmpty())
            return Map.of("ok", false, "error", String.format(NO_MAP_MSG, worldId));
        MapData.Province prov = map.provinces().get(name);
        if (prov == null) return Map.of("ok", false, "error", "Region not found: " + name);

        String hexKey = MapData.hexKey(q, r);
        List<String> newHexes = new ArrayList<>(prov.hexes());
        if (!newHexes.remove(hexKey))
            return resultMap("ok", true, "name", name, "hexCount", newHexes.size(), "note", "hex was not in region");

        Map<String, MapData.Province> updated = new LinkedHashMap<>(map.provinces());
        updated.put(
                name, new MapData.Province(newHexes, prov.color(), prov.tag(), prov.description(), prov.annexedBy()));
        MapData result = withProvinces(map, updated);
        saveMap(worldId, nodeId, result);
        return resultMap("ok", true, "name", name, "hexCount", newHexes.size(), "removed", hexKey);
    }

    /**
     * Create a new region with optional initial hex list.
     * @return result map with ok, name, hexCount, tag, color
     */
    public Map<String, Object> createRegion(
            String worldId,
            String nodeId,
            String name,
            String tag,
            String color,
            String description,
            List<String> hexes) {
        MapData map = resolve(worldId, nodeId);
        if (map == null || map.hexes().isEmpty())
            return Map.of("ok", false, "error", String.format(NO_MAP_MSG, worldId));
        if (map.provinces().containsKey(name)) return Map.of("ok", false, "error", "Region already exists: " + name);

        String t = tag != null ? tag : "";
        String c = color != null
                ? color
                : String.format("#%06x", ThreadLocalRandom.current().nextInt(0xFFFFFF) | 0x404040);
        String d = description != null ? description : "";
        List<String> h = hexes != null ? new ArrayList<>(hexes) : new ArrayList<>();

        Map<String, MapData.Province> updated = new LinkedHashMap<>(map.provinces());
        updated.put(name, new MapData.Province(h, c, t, d));
        MapData result = withProvinces(map, updated);
        saveMap(worldId, nodeId, result);
        return resultMap("ok", true, "name", name, "hexCount", h.size(), "tag", t, "color", c);
    }

    /**
     * Delete a region by name.
     * @return result map with ok, name, action
     */
    public Map<String, Object> deleteRegion(String worldId, String nodeId, String name) {
        MapData map = resolve(worldId, nodeId);
        if (map == null || map.hexes().isEmpty())
            return Map.of("ok", false, "error", String.format(NO_MAP_MSG, worldId));
        if (!map.provinces().containsKey(name)) return Map.of("ok", false, "error", "Region not found: " + name);

        Map<String, MapData.Province> updated = new LinkedHashMap<>(map.provinces());
        updated.remove(name);
        MapData result = withProvinces(map, updated);
        saveMap(worldId, nodeId, result);
        return resultMap("ok", true, "name", name, "action", "deleted");
    }

    /**
     * Merge two regions: dominant absorbs annexed.
     * The annexed region keeps all its original data but is marked with annexedBy = dominantName.
     * The dominant region's hexes expand to include all of the annexed region's hexes.
     * The annexed region is no longer rendered on the map.
     *
     * @param worldId       the world identifier
     * @param nodeId        the node identifier
     * @param dominantName  the region that absorbs the other
     * @param annexedName   the region being absorbed
     * @return result map with ok, dominantName, annexedName, transferredHexes
     */
    public Map<String, Object> mergeRegions(String worldId, String nodeId, String dominantName, String annexedName) {
        MapData map = resolve(worldId, nodeId);
        if (map == null || map.hexes().isEmpty())
            return Map.of("ok", false, "error", String.format(NO_MAP_MSG, worldId));

        MapData.Province dominant = map.provinces().get(dominantName);
        if (dominant == null) return Map.of("ok", false, "error", "Dominant region not found: " + dominantName);

        MapData.Province annexed = map.provinces().get(annexedName);
        if (annexed == null) return Map.of("ok", false, "error", "Annexed region not found: " + annexedName);

        if (dominantName.equals(annexedName)) return Map.of("ok", false, "error", "Cannot merge a region into itself");

        if (!annexed.annexedBy().isBlank())
            return Map.of(
                    "ok",
                    false,
                    "error",
                    "Region '" + annexedName + "' has already been annexed by " + annexed.annexedBy());

        if (!dominant.annexedBy().isBlank())
            return Map.of(
                    "ok",
                    false,
                    "error",
                    "Dominant region '" + dominantName + "' has been annexed and cannot absorb others");

        // Transfer hexes from annexed to dominant
        Set<String> mergedHexes = new LinkedHashSet<>(dominant.hexes());
        mergedHexes.addAll(annexed.hexes());
        List<String> newDominantHexes = new ArrayList<>(mergedHexes);

        // Keep annexed with all original info, just mark annexedBy
        MapData.Province newAnnexed = new MapData.Province(
                annexed.hexes(), annexed.color(), annexed.tag(), annexed.description(), dominantName);

        MapData.Province newDominant = new MapData.Province(
                newDominantHexes, dominant.color(), dominant.tag(), dominant.description(), dominant.annexedBy());

        Map<String, MapData.Province> updated = new LinkedHashMap<>(map.provinces());
        updated.put(dominantName, newDominant);
        updated.put(annexedName, newAnnexed);

        MapData result = withProvinces(map, updated);
        saveMap(worldId, nodeId, result);

        int transferred = annexed.hexes().size();
        int newTotal = newDominantHexes.size();
        log.info(
                "Merged '{}' into '{}': {} hexes transferred (dominant now has {} total) in {}/{}",
                annexedName,
                dominantName,
                transferred,
                newTotal,
                worldId,
                nodeId);

        return resultMap(
                "ok", true,
                "dominantName", dominantName,
                "annexedName", annexedName,
                "transferredHexes", transferred,
                "dominantHexCount", newTotal);
    }

    /**
     * Update a terrain type definition, preserving unspecified fields.
     * @return result map with ok, key, name, color, food, gold, stone, moveCost
     */
    public Map<String, Object> updateTerrainType(
            String worldId,
            String nodeId,
            String key,
            String name,
            String color,
            Integer food,
            Integer gold,
            Integer stone,
            Integer moveCost,
            String description) {
        MapData map = resolve(worldId, nodeId);
        if (map == null || map.hexes().isEmpty())
            return Map.of("ok", false, "error", String.format(NO_MAP_MSG, worldId));
        if (!map.terrainTypes().containsKey(key)) return Map.of("ok", false, "error", "Terrain type not found: " + key);

        MapData.TerrainType existing = map.terrainTypes().get(key);
        String newName = name != null ? name : existing.name();
        String newColor = color != null ? color : existing.color();
        int newFood = food != null ? food : existing.food();
        int newGold = gold != null ? gold : existing.gold();
        int newStone = stone != null ? stone : existing.stone();
        int newMoveCost = moveCost != null ? moveCost : existing.moveCost();
        String newDesc = description != null ? description : existing.description();

        Map<String, MapData.TerrainType> updated = new LinkedHashMap<>(map.terrainTypes());
        updated.put(key, new MapData.TerrainType(newName, newColor, newFood, newGold, newStone, newMoveCost, newDesc));
        MapData result = withTerrainTypes(map, updated);
        saveMap(worldId, nodeId, result);
        return resultMap(
                "ok",
                true,
                "key",
                key,
                "name",
                newName,
                "color",
                newColor,
                "food",
                newFood,
                "gold",
                newGold,
                "stone",
                newStone,
                "moveCost",
                newMoveCost);
    }

    /**
     * Initialize a new nation by flood-filling unowned hexes, creating a province,
     * and optionally writing checkpoint elements.
     * @return result map with ok, name, hexCount, tag, color, center, checkpointsCreated
     */
    public Map<String, Object> initNation(
            String worldId,
            String nodeId,
            String name,
            int seedQ,
            int seedR,
            int maxHexes,
            String tag,
            String color,
            String faction,
            String narrative,
            String worldview,
            String capital,
            String ruler,
            String religion)
            throws IOException {
        return initNation(
                worldId, nodeId, name, seedQ, seedR, maxHexes, tag, color, faction, narrative, worldview, capital,
                ruler, religion, false);
    }

    /** initNation with autoGenerate option — creates terrain if none exists. */
    public Map<String, Object> initNation(
            String worldId,
            String nodeId,
            String name,
            int seedQ,
            int seedR,
            int maxHexes,
            String tag,
            String color,
            String faction,
            String narrative,
            String worldview,
            String capital,
            String ruler,
            String religion,
            boolean autoGenerate)
            throws IOException {
        MapData map = resolve(worldId, nodeId);
        if (map == null || map.hexes().isEmpty()) {
            if (autoGenerate) {
                long seed = System.currentTimeMillis();
                generate(worldId, nodeId, seed, 80, 2, 5, 0.55, 0.6);
                map = resolve(worldId, nodeId); // re-resolve to get fresh data
                if (map == null || map.hexes().isEmpty())
                    return Map.of("ok", false, "error", "Auto-generated map is empty for " + worldId);
            } else {
                return Map.of("ok", false, "error", String.format(NO_MAP_MSG, worldId));
            }
        }
        if (map.provinces().containsKey(name)) return Map.of("ok", false, "error", "Region already exists: " + name);

        String seedKey = MapData.hexKey(seedQ, seedR);
        if (!map.hexes().containsKey(seedKey)) return Map.of("ok", false, "error", "Seed hex not on map: " + seedKey);

        // Build owned hex set
        Set<String> owned = new HashSet<>();
        for (var p : map.provinces().values()) {
            owned.addAll(p.hexes());
        }
        if (owned.contains(seedKey)) return Map.of("ok", false, "error", "Seed hex already owned");

        // 1. Flood-fill unowned hexes
        Set<String> collected = new LinkedHashSet<>();
        var queue = new ArrayDeque<String>();
        queue.add(seedKey);
        collected.add(seedKey);
        while (!queue.isEmpty() && collected.size() < maxHexes) {
            String cur = queue.poll();
            int[] qr = MapData.parseHexKey(cur);
            for (int[] d : HEX_DIRS) {
                String nk = MapData.hexKey(qr[0] + d[0], qr[1] + d[1]);
                if (map.hexes().containsKey(nk) && !owned.contains(nk) && collected.add(nk)) {
                    queue.add(nk);
                }
            }
        }
        if (collected.isEmpty()) return Map.of("ok", false, "error", "No unowned hexes reachable from seed");

        // 2. Create province
        String t = tag != null ? tag : "Nation";
        String c = color != null
                ? color
                : String.format("#%06x", ThreadLocalRandom.current().nextInt(0xFFFFFF) | 0x404040);
        List<String> hexList = new ArrayList<>(collected);

        Map<String, MapData.Province> updatedProvinces = new LinkedHashMap<>(map.provinces());
        updatedProvinces.put(name, new MapData.Province(hexList, c, t, ""));
        MapData result = withProvinces(map, updatedProvinces);
        saveMap(worldId, nodeId, result);

        // 3. Checkpoint entries managed by GSim Core via write_element

        // 4. Compute center
        int sq = 0, sr = 0;
        for (String hk : hexList) {
            int[] qr = MapData.parseHexKey(hk);
            sq += qr[0];
            sr += qr[1];
        }

        log.info("init_nation '{}': {} hexes (checkpoint entries managed by GSim Core)", name, hexList.size());
        return resultMap(
                "ok",
                true,
                "name",
                name,
                "hexCount",
                hexList.size(),
                "tag",
                t,
                "color",
                c,
                "center",
                Map.of("q", Math.round((float) sq / hexList.size()), "r", Math.round((float) sr / hexList.size())));
    }

    // ── Helpers ───────────────────────────────────────────

    /**
     * Returns the active (leaf) node ID for a world by scanning the nodes directory.
     * Finds the node that is not referenced as a parent by any other node
     * (the leaf of the chain), falling back to {@code "n0000"}.
     *
     * @param worldId the world identifier
     * @return the leaf node ID, or {@code "n0000"} if discovery fails
     */
    public String readActiveNodeId(String worldId) {
        try {
            java.nio.file.Path nodesDir = com.gsim.worldinfo.loader.NodeLoader.nodesDir(worldsDir, worldId);
            if (!java.nio.file.Files.isDirectory(nodesDir)) return "n0000";

            // Load all nodes
            java.util.Map<String, com.gsim.worldinfo.NodeSnapshot> allNodes = new java.util.LinkedHashMap<>();
            java.util.regex.Pattern nodePattern = java.util.regex.Pattern.compile("n\\d{4}\\.json");
            try (var files = java.nio.file.Files.list(nodesDir)) {
                files.filter(f -> nodePattern.matcher(f.getFileName().toString()).matches())
                        .forEach(f -> {
                            try {
                                var n = com.gsim.worldinfo.loader.NodeLoader.load(f);
                                allNodes.put(n.nodeId(), n);
                            } catch (RuntimeException ignored) {
                            }
                        });
            }

            if (allNodes.isEmpty()) return "n0000";

            // Find nodeIds referenced as parents
            java.util.Set<String> parents = new java.util.HashSet<>();
            for (var n : allNodes.values()) {
                if (n.parentId() != null && !n.isRoot()) {
                    parents.add(n.parentId());
                }
            }

            // Leaf: node NOT referenced as parent, highest turn wins
            com.gsim.worldinfo.NodeSnapshot leaf = null;
            for (var n : allNodes.values()) {
                if (!parents.contains(n.nodeId())) {
                    if (leaf == null || n.turn() > leaf.turn()) {
                        leaf = n;
                    }
                }
            }

            if (leaf != null) return leaf.nodeId();
        } catch (Exception e) {
            log.warn("Failed to discover active node for world {}: {}", worldId, e.getMessage());
        }
        return "n0000";
    }

    public String readParentId(String worldId, String nodeId) {
        try {
            var node = com.gsim.worldinfo.loader.NodeLoader.load(
                    com.gsim.worldinfo.loader.NodeLoader.nodeFile(worldsDir, worldId, nodeId));
            String pid = node.parentId();
            return (pid != null && !pid.isBlank()) ? pid : "n0000";
        } catch (RuntimeException e) {
            // Corrupt or missing node file — log loudly so this doesn't go unnoticed
            log.warn(
                    "Failed to read parentId for node {} (file may be corrupt or missing): {}", nodeId, e.getMessage());
            return "n0000";
        }
    }
}
