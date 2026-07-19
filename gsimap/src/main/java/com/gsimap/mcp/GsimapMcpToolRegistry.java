package com.gsimap.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.gsim.util.JsonUtils;
import com.gsimap.map.MapData;
import com.gsimap.map.MapDiff;
import com.gsimap.map.MapResolver;
import com.gsimap.map.MapStore;
import com.gsimap.service.MapService;
import com.gsim.mcp.ToolDef;
import com.gsim.mcp.UnknownToolException;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Registry of MCP tools exposed by Gsimap.
 * All tools are prefixed "gsimap_" for Hermes auto-discovery.
 */
public class GsimapMcpToolRegistry implements com.gsim.mcp.McpToolRegistry {

    private static final Logger log = LoggerFactory.getLogger(GsimapMcpToolRegistry.class);
    private static final com.fasterxml.jackson.databind.ObjectMapper MAPPER = JsonUtils.MAPPER;

    private final MapService mapService;
    private final Map<String, ToolDef> tools = new LinkedHashMap<>();

    /**
     * Creates a tool registry and registers all gsimap_* MCP tools.
     *
     * @param mapService the shared map service instance
     */
    @SuppressFBWarnings("EI_EXPOSE_REP2") // MapService is a shared service class, not a data object
    public GsimapMcpToolRegistry(MapService mapService) {
        this.mapService = mapService;
        registerAll();
    }

    /**
     * Returns an immutable snapshot of all registered tool definitions.
     *
     * @return list of all tool definitions
     */
    @Override
    public List<ToolDef> all() {
        return List.copyOf(tools.values());
    }

    /**
     * Execute the named tool with the given JSON arguments.
     *
     * @param name the tool name (must be registered)
     * @param args the JSON arguments
     * @return JSON result string
     * @throws IOException if JSON serialization fails
     * @throws IllegalArgumentException if the tool name is unknown
     */
    @Override
    public String execute(String name, JsonNode args) throws Exception {
        ToolDef tool = tools.get(name);
        if (tool == null) throw new UnknownToolException(name);
        return switch (name) {
            case "gsimap_get_hex" -> handleGetHex(args);
            case "gsimap_get_province" -> handleGetProvince(args);
            case "gsimap_get_neighbors" -> handleGetNeighbors(args);
            case "gsimap_query_radius" -> handleQueryRadius(args);
            case "gsimap_get_cities" -> handleGetCities(args);
            case "gsimap_get_diff" -> handleGetDiff(args);
            case "gsimap_get_history" -> handleGetHistory(args);
            case "gsimap_find_river_path" -> handleFindRiverPath(args);
            case "gsimap_list_regions" -> handleListRegions(args);
            case "gsimap_get_distance" -> handleGetDistance(args);
            case "gsimap_update_region" -> handleUpdateRegion(args);
            case "gsimap_add_hex_to_region" -> handleAddHexToRegion(args);
            case "gsimap_remove_hex_from_region" -> handleRemoveHexFromRegion(args);
            case "gsimap_create_region" -> handleCreateRegion(args);
            case "gsimap_delete_region" -> handleDeleteRegion(args);
            case "gsimap_list_checkpoints" -> handleListCheckpoints(args);
            case "gsimap_get_checkpoint" -> handleGetCheckpoint(args);
            case "gsimap_add_checkpoint_element" -> handleAddCheckpointElement(args);
            case "gsimap_update_checkpoint_element" -> handleUpdateCheckpointElement(args);
            case "gsimap_delete_checkpoint_element" -> handleDeleteCheckpointElement(args);
            case "gsimap_rename_region" -> handleRenameRegion(args);
            case "gsimap_generate" -> handleGenerate(args);
            case "gsimap_init_nation" -> handleInitNation(args);
            case "gsimap_update_terrain_type" -> handleUpdateTerrainType(args);
            default -> throw new UnknownToolException(name);
        };
    }

    // ── Registration ──────────────────────────────────────

    private void registerAll() {
        registerQueryTools();
        registerDiffTools();
        registerRegionTools();
        registerCheckpointTools();
        registerInitTools();
    }

    private void registerQueryTools() {
        register(
                "gsimap_get_hex",
                "Query a single hex cell by coordinates. Returns color, terrain type, symbol, and province ownership.",
                """
            {"type":"object","properties":{
              "worldId":{"type":"string","description":"GSim world ID"},
              "nodeId":{"type":"string","description":"Node ID (optional, defaults to active node)"},
              "q":{"type":"integer","description":"Axial q coordinate"},
              "r":{"type":"integer","description":"Axial r coordinate"}
            },"required":["worldId","q","r"]}""");

        register(
                "gsimap_get_province",
                "Query a province by name. Returns all hex cells belonging to it.",
                """
            {"type":"object","properties":{
              "worldId":{"type":"string"},
              "nodeId":{"type":"string"},
              "name":{"type":"string","description":"Province name"}
            },"required":["worldId","name"]}""");

        register(
                "gsimap_get_neighbors",
                "Get all 6 neighboring hex cells of a given coordinate.",
                """
            {"type":"object","properties":{
              "worldId":{"type":"string"},
              "nodeId":{"type":"string"},
              "q":{"type":"integer"},
              "r":{"type":"integer"}
            },"required":["worldId","q","r"]}""");

        register(
                "gsimap_query_radius",
                "Query all hex cells within a given radius of a center coordinate.",
                """
            {"type":"object","properties":{
              "worldId":{"type":"string"},
              "nodeId":{"type":"string"},
              "q":{"type":"integer"},
              "r":{"type":"integer"},
              "radius":{"type":"integer","description":"Search radius in hex steps"}
            },"required":["worldId","q","r","radius"]}""");

        register(
                "gsimap_get_cities",
                "List all cities on the map with their coordinates.",
                """
            {"type":"object","properties":{
              "worldId":{"type":"string"},
              "nodeId":{"type":"string"}
            },"required":["worldId"]}""");

        register(
                "gsimap_find_river_path",
                "Find the minimum-cost river path from a source hex to the nearest water "
                        + "or map edge. Uses terrain moveCost as edge weight.",
                """
            {"type":"object","properties":{
              "worldId":{"type":"string"},
              "nodeId":{"type":"string"},
              "q":{"type":"integer"},
              "r":{"type":"integer"}
            },"required":["worldId","q","r"]}""");

        register(
                "gsimap_list_regions",
                "List all regions with center coordinates, terrain composition, "
                        + "and adjacent region relationships.",
                """
            {"type":"object","properties":{
              "worldId":{"type":"string"},
              "nodeId":{"type":"string"}
            },"required":["worldId"]}""");

        register(
                "gsimap_get_distance",
                "Calculate hex distance between two points (by coordinates or region names).",
                """
            {"type":"object","properties":{
              "worldId":{"type":"string"},
              "nodeId":{"type":"string"},
              "fromQ":{"type":"integer"},
              "fromR":{"type":"integer"},
              "toQ":{"type":"integer"},
              "toR":{"type":"integer"},
              "fromRegion":{"type":"string"},
              "toRegion":{"type":"string"}
            },"required":["worldId"]}""");
    }

    private void registerDiffTools() {
        register(
                "gsimap_get_diff",
                "Get the map changes (diff) for a specific node. Shows what changed this turn.",
                """
            {"type":"object","properties":{
              "worldId":{"type":"string"},
              "nodeId":{"type":"string"}
            },"required":["worldId","nodeId"]}""");

        register(
                "gsimap_get_history",
                "Get the map history across all nodes in the chain.",
                """
            {"type":"object","properties":{
              "worldId":{"type":"string"},
              "nodeId":{"type":"string"}
            },"required":["worldId"]}""");
    }

    private void registerRegionTools() {
        register(
                "gsimap_update_region",
                "Update a region's properties (hexes, tag, description, color). " + "Auto-saves after change.",
                """
            {"type":"object","properties":{
              "worldId":{"type":"string"},
              "nodeId":{"type":"string"},
              "name":{"type":"string","description":"Region name"},
              "tag":{"type":"string","description":"New tag (optional)"},
              "description":{"type":"string","description":"New description (optional)"},
              "color":{"type":"string","description":"New hex color (optional)"},
              "hexes":{"type":"array",
                "items":{"type":"string"},
                "description":"New hex key list e.g. ['10_-5','11_-5'] (optional)"}
            },"required":["worldId","name"]}""");

        register(
                "gsimap_add_hex_to_region",
                "Add a single hex to a region. Auto-saves after change.",
                """
            {"type":"object","properties":{
              "worldId":{"type":"string"},
              "nodeId":{"type":"string"},
              "name":{"type":"string","description":"Region name"},
              "q":{"type":"integer"},
              "r":{"type":"integer"}
            },"required":["worldId","name","q","r"]}""");

        register(
                "gsimap_remove_hex_from_region",
                "Remove a single hex from a region. Auto-saves after change.",
                """
            {"type":"object","properties":{
              "worldId":{"type":"string"},
              "nodeId":{"type":"string"},
              "name":{"type":"string","description":"Region name"},
              "q":{"type":"integer"},
              "r":{"type":"integer"}
            },"required":["worldId","name","q","r"]}""");

        register(
                "gsimap_create_region",
                "Create a new empty region. Auto-saves.",
                """
            {"type":"object","properties":{
              "worldId":{"type":"string"},
              "nodeId":{"type":"string"},
              "name":{"type":"string","description":"New region name"},
              "tag":{"type":"string","description":"Tag (optional)"},
              "color":{"type":"string","description":"Hex color (optional, default auto-generated)"},
              "description":{"type":"string","description":"Description (optional)"},
              "hexes":{"type":"array","items":{"type":"string"},
                "description":"Initial hex keys (optional, default empty)"}
            },"required":["worldId","name"]}""");

        register(
                "gsimap_delete_region",
                "Delete a region. Auto-saves.",
                """
            {"type":"object","properties":{
              "worldId":{"type":"string"},
              "nodeId":{"type":"string"},
              "name":{"type":"string","description":"Region name to delete"}
            },"required":["worldId","name"]}""");

        register(
                "gsimap_rename_region",
                "Rename a region across all data stores: MapData provinces + all GSim "
                        + "checkpoint references (factions, narrative, map, etc.). "
                        + "Updates keys, tags, and text references. Auto-saves.",
                """
            {"type":"object","properties":{
              "worldId":{"type":"string"},
              "nodeId":{"type":"string"},
              "oldName":{"type":"string","description":"Current region name"},
              "newName":{"type":"string","description":"New region name"}
            },"required":["worldId","oldName","newName"]}""");
    }

    private void registerCheckpointTools() {
        // ── Checkpoint (document) tools ───────────────────

        register(
                "gsimap_list_checkpoints",
                "List all checkpoints in a GSim node (narrative, factions, worldview, "
                        + "characters, map). Returns name, label, type, and element count for each.",
                """
            {"type":"object","properties":{
              "worldId":{"type":"string"},
              "nodeId":{"type":"string","description":"Node ID (optional, defaults to active node)"}
            },"required":["worldId"]}""");

        register(
                "gsimap_get_checkpoint",
                "Get elements from a GSim checkpoint. Optionally filter by element key or tags. "
                        + "Use this to read narrative entries, faction descriptions, worldview docs, "
                        + "or character states.",
                """
            {"type":"object","properties":{
              "worldId":{"type":"string"},
              "nodeId":{"type":"string"},
              "checkpoint":{"type":"string",
                "description":"Checkpoint name: narrative, factions, worldview, characters, map"},
              "key":{"type":"string","description":"Filter by specific element key (optional)"},
              "tags":{"type":"array","items":{"type":"string"},
                "description":"Filter by tags — element must have ALL specified tags (optional)"}
            },"required":["worldId","checkpoint"]}""");

        register(
                "gsimap_add_checkpoint_element",
                "Add a new element to a GSim checkpoint. Use to create narrative entries, "
                        + "faction descriptions, character states, or worldview documents.",
                """
            {"type":"object","properties":{
              "worldId":{"type":"string"},
              "nodeId":{"type":"string"},
              "checkpoint":{"type":"string",
                "description":"Checkpoint name: narrative, factions, worldview, characters, map"},
              "key":{"type":"string","description":"Unique element key (e.g. '大汉开局')"},
              "type":{"type":"string",
                "description":"Element type: text, character_state, map-region, map-city (default: text)"},
              "value":{"type":"string","description":"Full text content of the element"},
              "tags":{"type":"array","items":{"type":"string"},
                "description":"Tags for categorization and filtering (e.g. ['开局','大汉','推文'])"}
            },"required":["worldId","checkpoint","key","value"]}""");

        register(
                "gsimap_update_checkpoint_element",
                "Update an existing element in a GSim checkpoint. " + "Only provided fields are changed.",
                """
            {"type":"object","properties":{
              "worldId":{"type":"string"},
              "nodeId":{"type":"string"},
              "checkpoint":{"type":"string","description":"Checkpoint name"},
              "key":{"type":"string","description":"Element key to update"},
              "value":{"type":"string","description":"New text content (optional)"},
              "type":{"type":"string","description":"New element type (optional)"},
              "tags":{"type":"array","items":{"type":"string"},
                "description":"New tags list (optional, replaces all existing tags)"}
            },"required":["worldId","checkpoint","key"]}""");

        register(
                "gsimap_delete_checkpoint_element",
                "Delete an element from a GSim checkpoint by key.",
                """
            {"type":"object","properties":{
              "worldId":{"type":"string"},
              "nodeId":{"type":"string"},
              "checkpoint":{"type":"string","description":"Checkpoint name"},
              "key":{"type":"string","description":"Element key to delete"}
            },"required":["worldId","checkpoint","key"]}""");
    }

    private void registerInitTools() {
        register(
                "gsimap_generate",
                "Generate a full terrain hex map for a world using procedural generation. "
                        + "Creates continents with mountain ridges, lowlands, and water. "
                        + "Required before using gsimap_init_nation.",
                """
            {"type":"object","properties":{
              "worldId":{"type":"string","description":"GSim world ID"},
              "nodeId":{"type":"string","description":"Node ID (default: n0000)"},
              "seed":{"type":"integer","description":"Random seed (default: current time)"},
              "radius":{"type":"integer","description":"Map radius in hex steps (default: 80)"},
              "ridges":{"type":"integer","description":"Number of main mountain ridges (default: 2)"},
              "fragments":{"type":"integer","description":"Number of fragment ridges (default: 5)"},
              "landRatio":{"type":"number","description":"Target land ratio 0..1 (default: 0.55)"},
              "coastRoughness":{"type":"number","description":"Coast roughness 0..1 (default: 0.6)"}
            },"required":["worldId"]}""");

        register(
                "gsimap_init_nation",
                "One-shot nation initialization: flood-fill unowned hexes from a seed, "
                        + "create the MapData province, sync to GSim map checkpoint, and optionally "
                        + "write faction/narrative/worldview checkpoint entries and a capital city. "
                        + "Use this to bootstrap countries in unexplored territory.",
                """
            {"type":"object","properties":{
              "worldId":{"type":"string"},
              "nodeId":{"type":"string"},
              "name":{"type":"string","description":"Nation/province name"},
              "seedQ":{"type":"integer","description":"Seed hex q (flood-fill start point)"},
              "seedR":{"type":"integer","description":"Seed hex r"},
              "maxHexes":{"type":"integer","description":"Max hexes to collect (default 1000)"},
              "tag":{"type":"string","description":"Region tag (default: 'Nation')"},
              "color":{"type":"string","description":"Region color hex (default: auto-generated)"},
              "faction":{"type":"string","description":"Faction description text → factions checkpoint"},
              "narrative":{"type":"string","description":"Opening narrative text → narrative checkpoint"},
              "worldview":{"type":"string","description":"Worldview text → worldview checkpoint (optional)"},
              "capital":{"type":"string","description":"Capital city name → creates city element in map checkpoint"},
              "ruler":{"type":"string","description":"Ruler name (optional, appended to faction tags)"},
              "religion":{"type":"string","description":"Religion (optional, appended to faction tags)"}
            },"required":["worldId","name","seedQ","seedR"]}""");

        register(
                "gsimap_update_terrain_type",
                "Update a terrain type definition (name, color, food, gold, stone, moveCost, "
                        + "description). Provide only the fields you want to change.",
                """
            {"type":"object","properties":{
              "worldId":{"type":"string","description":"GSim world ID"},
              "nodeId":{"type":"string","description":"Node ID (optional, defaults to active node)"},
              "key":{"type":"string",
                "description":"Terrain key: water, lowland, hills, plains, mountain, swamp, desert, tundra, forest"},
              "name":{"type":"string","description":"New display name (e.g. '山区')"},
              "color":{"type":"string","description":"New hex color (e.g. '#B8A88A')"},
              "food":{"type":"integer","description":"Food output"},
              "gold":{"type":"integer","description":"Gold output"},
              "stone":{"type":"integer","description":"Stone output"},
              "moveCost":{"type":"integer","description":"Movement cost"},
              "description":{"type":"string","description":"Tooltip description"}
            },"required":["worldId","key"]}""");
    }

    private void register(String name, String description, String schema) {
        tools.put(name, new ToolDef(name, description, schema));
    }

    // ── Tool implementations ──────────────────────────────

    private String handleGetHex(JsonNode args) throws IOException {
        String worldId = args.get("worldId").asText();
        String nodeId = args.has("nodeId") ? args.get("nodeId").asText() : null;
        int q = args.get("q").asInt();
        int r = args.get("r").asInt();
        MapData map = nodeId != null ? mapService.resolve(worldId, nodeId) : mapService.resolveActive(worldId);
        String key = MapData.hexKey(q, r);
        MapData.HexCell cell = map.hexes().get(key);
        if (cell == null) return toJson(Map.of("found", false, "q", q, "r", r));
        // Find owning province
        String province = map.provinces().entrySet().stream()
                .filter(e -> e.getValue().hexes().contains(key))
                .map(Map.Entry::getKey)
                .findFirst()
                .orElse(null);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("found", true);
        result.put("q", q);
        result.put("r", r);
        result.put("color", cell.color());
        result.put("terrain", cell.terrain());
        if (cell.symbol() != null) result.put("symbol", cell.symbol());
        if (cell.description() != null && !cell.description().isEmpty()) result.put("description", cell.description());
        if (province != null) result.put("province", province);
        // Include terrain properties
        MapData.TerrainType tt = map.terrainTypes().get(cell.terrain());
        if (tt != null) {
            result.put("food", tt.food());
            result.put("gold", tt.gold());
            result.put("stone", tt.stone());
            result.put("moveCost", tt.moveCost());
        }
        return toJson(result);
    }

    private String handleGetProvince(JsonNode args) throws IOException {
        String worldId = args.get("worldId").asText();
        String nodeId = args.has("nodeId") ? args.get("nodeId").asText() : null;
        String name = args.get("name").asText();
        MapData map = nodeId != null ? mapService.resolve(worldId, nodeId) : mapService.resolveActive(worldId);
        MapData.Province prov = map.provinces().get(name);
        if (prov == null) return toJson(Map.of("found", false, "name", name));
        List<Map<String, Object>> hexes = new ArrayList<>();
        for (String key : prov.hexes()) {
            MapData.HexCell cell = map.hexes().get(key);
            int[] coords = MapData.parseHexKey(key);
            Map<String, Object> h = new LinkedHashMap<>();
            h.put("q", coords[0]);
            h.put("r", coords[1]);
            if (cell != null) {
                h.put("color", cell.color());
                h.put("terrain", cell.terrain());
                if (cell.symbol() != null) h.put("symbol", cell.symbol());
            }
            hexes.add(h);
        }
        // Build adjacency index for all regions
        Map<String, Set<String>> regionHexSets = new LinkedHashMap<>();
        for (var e : map.provinces().entrySet()) {
            regionHexSets.put(e.getKey(), new HashSet<>(e.getValue().hexes()));
        }
        Set<String> ownSet = regionHexSets.get(name);
        List<Map<String, Object>> adj = ownSet != null ? MapService.computeAdjacency(ownSet, regionHexSets) : List.of();
        int[] center = MapService.computeCenter(prov.hexes());
        Map<String, Integer> terrainComp = MapService.computeTerrainComposition(prov, map);

        return toJson(Map.of(
                "found",
                true,
                "name",
                name,
                "hexCount",
                hexes.size(),
                "hexes",
                hexes,
                "tag",
                prov.tag() != null ? prov.tag() : "",
                "description",
                prov.description() != null ? prov.description() : "",
                "center",
                Map.of("q", center[0], "r", center[1]),
                "adjacentRegions",
                adj,
                "adjacentCount",
                adj.size(),
                "terrainComposition",
                terrainComp));
    }

    private String handleGetNeighbors(JsonNode args) throws IOException {
        String worldId = args.get("worldId").asText();
        String nodeId = args.has("nodeId") ? args.get("nodeId").asText() : null;
        int q = args.get("q").asInt();
        int r = args.get("r").asInt();
        MapData map = nodeId != null ? mapService.resolve(worldId, nodeId) : mapService.resolveActive(worldId);
        int[][] dirs = {{1, 0}, {1, -1}, {0, -1}, {-1, 0}, {-1, 1}, {0, 1}};
        List<Map<String, Object>> neighbors = new ArrayList<>();
        for (int[] d : dirs) {
            String key = MapData.hexKey(q + d[0], r + d[1]);
            MapData.HexCell cell = map.hexes().get(key);
            Map<String, Object> n = new LinkedHashMap<>();
            n.put("q", q + d[0]);
            n.put("r", r + d[1]);
            n.put("exists", cell != null);
            if (cell != null) {
                n.put("color", cell.color());
                n.put("terrain", cell.terrain());
            }
            neighbors.add(n);
        }
        return toJson(Map.of("center", Map.of("q", q, "r", r), "neighbors", neighbors));
    }

    private String handleQueryRadius(JsonNode args) throws IOException {
        String worldId = args.get("worldId").asText();
        String nodeId = args.has("nodeId") ? args.get("nodeId").asText() : null;
        int cq = args.get("q").asInt();
        int cr = args.get("r").asInt();
        int radius = args.get("radius").asInt();
        MapData map = nodeId != null ? mapService.resolve(worldId, nodeId) : mapService.resolveActive(worldId);
        List<Map<String, Object>> results = new ArrayList<>();
        for (int dq = -radius; dq <= radius; dq++) {
            for (int dr = Math.max(-radius, -dq - radius); dr <= Math.min(radius, -dq + radius); dr++) {
                String key = MapData.hexKey(cq + dq, cr + dr);
                MapData.HexCell cell = map.hexes().get(key);
                if (cell != null) {
                    Map<String, Object> h = new LinkedHashMap<>();
                    h.put("q", cq + dq);
                    h.put("r", cr + dr);
                    h.put("color", cell.color());
                    h.put("terrain", cell.terrain());
                    results.add(h);
                }
            }
        }
        return toJson(Map.of(
                "center", Map.of("q", cq, "r", cr), "radius", radius, "count", results.size(), "hexes", results));
    }

    private String handleGetCities(JsonNode args) throws IOException {
        String worldId = args.get("worldId").asText();
        String nodeId = args.has("nodeId") ? args.get("nodeId").asText() : null;
        MapData map = nodeId != null ? mapService.resolve(worldId, nodeId) : mapService.resolveActive(worldId);
        List<Map<String, Object>> cities = new ArrayList<>();
        for (var e : map.cities().entrySet()) {
            Map<String, Object> c = new LinkedHashMap<>();
            c.put("name", e.getKey());
            c.put("q", e.getValue().q());
            c.put("r", e.getValue().r());
            String key = MapData.hexKey(e.getValue().q(), e.getValue().r());
            MapData.HexCell cell = map.hexes().get(key);
            if (cell != null) c.put("terrain", cell.terrain());
            cities.add(c);
        }
        return toJson(Map.of("cities", cities));
    }

    private String handleGetDiff(JsonNode args) throws IOException {
        String worldId = args.get("worldId").asText();
        String nodeId = args.get("nodeId").asText();
        MapDiff diff = MapStore.loadDiff(mapService.getWorldsDir(), worldId, nodeId);
        if (diff == null) return toJson(Map.of("hasDiff", false, "nodeId", nodeId));
        return toJson(Map.of(
                "hasDiff",
                true,
                "nodeId",
                nodeId,
                "changedCount",
                diff.changed().size(),
                "removedCount",
                diff.removed().size(),
                "changed",
                diff.changed().keySet(),
                "removed",
                diff.removed()));
    }

    private String handleGetHistory(JsonNode args) throws IOException {
        String worldId = args.get("worldId").asText();
        String nodeId = args.has("nodeId") ? args.get("nodeId").asText() : null;
        if (nodeId == null) {
            var worldsDir = mapService.getWorldsDir();
            var activeFile = worldsDir.resolve(worldId).resolve("active.json");
            if (java.nio.file.Files.exists(activeFile)) {
                var n = MAPPER.readTree(activeFile.toFile());
                if (n.has("nodeId")) nodeId = n.get("nodeId").asText();
            }
        }
        if (nodeId == null) return toJson(Map.of("error", "No active node"));
        List<MapResolver.HistoryEntry> history = mapService.history(worldId, nodeId);
        List<Map<String, Object>> entries = new ArrayList<>();
        for (var h : history) {
            entries.add(Map.of(
                    "nodeId",
                    h.nodeId(),
                    "hasOwnMap",
                    h.hasOwnMap(),
                    "hexCount",
                    h.map().hexes().size()));
        }
        return toJson(Map.of("worldId", worldId, "chain", entries));
    }

    private String handleFindRiverPath(JsonNode args) throws IOException {
        String worldId = args.get("worldId").asText();
        String nodeId = args.has("nodeId") ? args.get("nodeId").asText() : null;
        int q = args.get("q").asInt();
        int r = args.get("r").asInt();
        List<String> path = mapService.findRiverPath(worldId, nodeId, q, r);
        return toJson(Map.of("source", Map.of("q", q, "r", r), "path", path, "length", path.size()));
    }

    private String handleListRegions(JsonNode args) throws IOException {
        String worldId = args.get("worldId").asText();
        String nodeId = args.has("nodeId") ? args.get("nodeId").asText() : null;
        MapData map = nodeId != null ? mapService.resolve(worldId, nodeId) : mapService.resolveActive(worldId);

        if (map.provinces() == null || map.provinces().isEmpty()) {
            return toJson(Map.of("worldId", worldId, "regions", List.of(), "count", 0));
        }

        // Build adjacencies: for each region, find touching regions
        Map<String, Set<String>> regionHexSets = new LinkedHashMap<>();
        Map<String, MapData.Province> provs = map.provinces();
        for (var entry : provs.entrySet()) {
            regionHexSets.put(entry.getKey(), new HashSet<>(entry.getValue().hexes()));
        }

        List<Map<String, Object>> regions = new ArrayList<>();
        for (var entry : provs.entrySet()) {
            String name = entry.getKey();
            MapData.Province prov = entry.getValue();
            Set<String> hexSet = regionHexSets.get(name);

            Map<String, Object> r = new LinkedHashMap<>();
            r.put("name", name);
            r.put("tag", prov.tag() != null ? prov.tag() : "");
            r.put("hexCount", prov.hexes().size());

            // Center
            int[] center = MapService.computeCenter(prov.hexes());
            r.put("center", Map.of("q", center[0], "r", center[1]));

            // Terrain composition
            r.put("terrainComposition", MapService.computeTerrainComposition(prov, map));

            // Adjacent regions
            List<Map<String, Object>> adj = MapService.computeAdjacency(hexSet, regionHexSets);
            r.put("adjacentRegions", adj);
            r.put("adjacentCount", adj.size());

            regions.add(r);
        }

        return toJson(Map.of("worldId", worldId, "regions", regions, "count", regions.size()));
    }

    private String handleGetDistance(JsonNode args) throws IOException {
        String worldId = args.get("worldId").asText();
        String nodeId = args.has("nodeId") ? args.get("nodeId").asText() : null;
        MapData map = nodeId != null ? mapService.resolve(worldId, nodeId) : mapService.resolveActive(worldId);

        int fromQ;
        int fromR;
        int toQ;
        int toR;
        String fromLabel;
        String toLabel;

        if (args.has("fromRegion") && args.has("toRegion")) {
            MapData.Province fromP = map.provinces().get(args.get("fromRegion").asText());
            MapData.Province toP = map.provinces().get(args.get("toRegion").asText());
            if (fromP == null || toP == null) return toJson(Map.of("error", "Region not found"));
            int[] fc = MapService.computeCenter(fromP.hexes());
            int[] tc = MapService.computeCenter(toP.hexes());
            fromQ = fc[0];
            fromR = fc[1];
            toQ = tc[0];
            toR = tc[1];
            fromLabel = args.get("fromRegion").asText();
            toLabel = args.get("toRegion").asText();
        } else if (args.has("fromQ") && args.has("toQ")) {
            fromQ = args.get("fromQ").asInt();
            fromR = args.get("fromR").asInt();
            toQ = args.get("toQ").asInt();
            toR = args.get("toR").asInt();
            fromLabel = "(" + fromQ + "," + fromR + ")";
            toLabel = "(" + toQ + "," + toR + ")";
        } else {
            return toJson(Map.of("error", "Provide (fromQ,fromR,toQ,toR) or (fromRegion,toRegion)"));
        }

        int hexDist = MapService.hexDistance(fromQ, fromR, toQ, toR);
        return toJson(Map.of(
                "from",
                fromLabel,
                "to",
                toLabel,
                "fromCoord",
                Map.of("q", fromQ, "r", fromR),
                "toCoord",
                Map.of("q", toQ, "r", toR),
                "hexDistance",
                hexDist));
    }

    // ── Write tools (all delegate to MapService) ────────────

    private String handleUpdateRegion(JsonNode args) throws IOException {
        String worldId = args.get("worldId").asText();
        String nodeId = args.has("nodeId") ? args.get("nodeId").asText() : "n0000";
        String name = args.get("name").asText();
        String tag = args.has("tag") ? args.get("tag").asText() : null;
        String desc = args.has("description") ? args.get("description").asText() : null;
        String color = args.has("color") ? args.get("color").asText() : null;
        List<String> hexes = null;
        if (args.has("hexes")) {
            hexes = new ArrayList<>();
            for (JsonNode n : args.get("hexes")) hexes.add(n.asText());
        }
        return toJson(mapService.updateRegion(worldId, nodeId, name, tag, desc, color, hexes));
    }

    private String handleAddHexToRegion(JsonNode args) throws IOException {
        String worldId = args.get("worldId").asText();
        String nodeId = args.has("nodeId") ? args.get("nodeId").asText() : "n0000";
        String name = args.get("name").asText();
        int q = args.get("q").asInt();
        int r = args.get("r").asInt();
        return toJson(mapService.addHexToRegion(worldId, nodeId, name, q, r));
    }

    private String handleRemoveHexFromRegion(JsonNode args) throws IOException {
        String worldId = args.get("worldId").asText();
        String nodeId = args.has("nodeId") ? args.get("nodeId").asText() : "n0000";
        String name = args.get("name").asText();
        int q = args.get("q").asInt();
        int r = args.get("r").asInt();
        return toJson(mapService.removeHexFromRegion(worldId, nodeId, name, q, r));
    }

    private String handleCreateRegion(JsonNode args) throws IOException {
        String worldId = args.get("worldId").asText();
        String nodeId = args.has("nodeId") ? args.get("nodeId").asText() : "n0000";
        String name = args.get("name").asText();
        String tag = args.has("tag") ? args.get("tag").asText() : null;
        String desc = args.has("description") ? args.get("description").asText() : null;
        String color = args.has("color") ? args.get("color").asText() : null;
        List<String> hexes = null;
        if (args.has("hexes")) {
            hexes = new ArrayList<>();
            for (JsonNode n : args.get("hexes")) hexes.add(n.asText());
        }
        return toJson(mapService.createRegion(worldId, nodeId, name, tag, color, desc, hexes));
    }

    private String handleDeleteRegion(JsonNode args) throws IOException {
        String worldId = args.get("worldId").asText();
        String nodeId = args.has("nodeId") ? args.get("nodeId").asText() : "n0000";
        String name = args.get("name").asText();
        return toJson(mapService.deleteRegion(worldId, nodeId, name));
    }

    // ── Checkpoint tools ──────────────────────────────────

    private String handleListCheckpoints(JsonNode args) throws IOException {
        String worldId = args.get("worldId").asText();
        String nodeId = args.has("nodeId") ? args.get("nodeId").asText() : mapService.readActiveNodeId(worldId);
        if (nodeId == null) return toJson(Map.of("error", "No active node for world: " + worldId));
        return toJson(mapService.getCheckpointService().listCheckpoints(worldId, nodeId));
    }

    private String handleGetCheckpoint(JsonNode args) throws IOException {
        String worldId = args.get("worldId").asText();
        String nodeId = args.has("nodeId") ? args.get("nodeId").asText() : mapService.readActiveNodeId(worldId);
        if (nodeId == null) return toJson(Map.of("error", "No active node for world: " + worldId));
        String cp = args.get("checkpoint").asText();
        String key = args.has("key") ? args.get("key").asText() : null;
        List<String> tags = null;
        if (args.has("tags") && args.get("tags").isArray()) {
            tags = new ArrayList<>();
            for (JsonNode t : args.get("tags")) {
                tags.add(t.asText());
            }
        }
        return toJson(mapService.getCheckpointService().getCheckpoint(worldId, nodeId, cp, key, tags));
    }

    private String handleAddCheckpointElement(JsonNode args) throws IOException {
        String worldId = args.get("worldId").asText();
        String nodeId = args.has("nodeId") ? args.get("nodeId").asText() : mapService.readActiveNodeId(worldId);
        if (nodeId == null) return toJson(Map.of("error", "No active node for world: " + worldId));
        String cp = args.get("checkpoint").asText();
        String key = args.get("key").asText();
        String value = args.get("value").asText();
        String type = args.has("type") ? args.get("type").asText() : "text";
        List<String> tags = null;
        if (args.has("tags") && args.get("tags").isArray()) {
            tags = new ArrayList<>();
            for (JsonNode t : args.get("tags")) {
                tags.add(t.asText());
            }
        }
        return toJson(mapService.getCheckpointService().addElement(worldId, nodeId, cp, key, type, value, tags));
    }

    private String handleUpdateCheckpointElement(JsonNode args) throws IOException {
        String worldId = args.get("worldId").asText();
        String nodeId = args.has("nodeId") ? args.get("nodeId").asText() : mapService.readActiveNodeId(worldId);
        if (nodeId == null) return toJson(Map.of("error", "No active node for world: " + worldId));
        String cp = args.get("checkpoint").asText();
        String key = args.get("key").asText();
        String value = args.has("value") ? args.get("value").asText() : null;
        String type = args.has("type") ? args.get("type").asText() : null;
        List<String> tags = null;
        if (args.has("tags") && args.get("tags").isArray()) {
            tags = new ArrayList<>();
            for (JsonNode t : args.get("tags")) {
                tags.add(t.asText());
            }
        }
        return toJson(mapService.getCheckpointService().updateElement(worldId, nodeId, cp, key, value, type, tags));
    }

    private String handleDeleteCheckpointElement(JsonNode args) throws IOException {
        String worldId = args.get("worldId").asText();
        String nodeId = args.has("nodeId") ? args.get("nodeId").asText() : mapService.readActiveNodeId(worldId);
        if (nodeId == null) return toJson(Map.of("error", "No active node for world: " + worldId));
        String cp = args.get("checkpoint").asText();
        String key = args.get("key").asText();
        return toJson(mapService.getCheckpointService().deleteElement(worldId, nodeId, cp, key));
    }

    private String handleRenameRegion(JsonNode args) throws IOException {
        String worldId = args.get("worldId").asText();
        String nodeId = args.has("nodeId") ? args.get("nodeId").asText() : mapService.readActiveNodeId(worldId);
        if (nodeId == null) return toJson(Map.of("error", "No active node for world: " + worldId));
        String oldName = args.get("oldName").asText();
        String newName = args.get("newName").asText();
        return toJson(mapService.renameRegion(worldId, nodeId, oldName, newName));
    }

    private String handleGenerate(JsonNode args) throws IOException {
        String worldId = args.get("worldId").asText();
        String nodeId = args.has("nodeId") ? args.get("nodeId").asText() : "n0000";
        long seed = args.has("seed") ? args.get("seed").asLong() : System.currentTimeMillis();
        int radius = args.has("radius") ? args.get("radius").asInt() : 80;
        int ridges = args.has("ridges") ? args.get("ridges").asInt() : 2;
        int fragments = args.has("fragments") ? args.get("fragments").asInt() : 5;
        double landRatio = args.has("landRatio") ? args.get("landRatio").asDouble() : 0.55;
        double coastRoughness = args.has("coastRoughness") ? args.get("coastRoughness").asDouble() : 0.6;
        return toJson(mapService.generate(worldId, nodeId, seed, radius, ridges, fragments,
                landRatio, coastRoughness));
    }

    private String handleInitNation(JsonNode args) throws IOException {
        String worldId = args.get("worldId").asText();
        String nodeId = args.has("nodeId") ? args.get("nodeId").asText() : mapService.readActiveNodeId(worldId);
        if (nodeId == null) return toJson(Map.of("error", "No active node for world: " + worldId));
        String name = args.get("name").asText();
        int seedQ = args.get("seedQ").asInt();
        int seedR = args.get("seedR").asInt();
        int maxHexes = args.has("maxHexes") ? args.get("maxHexes").asInt() : 1000;
        String tag = args.has("tag") ? args.get("tag").asText() : null;
        String color = args.has("color") ? args.get("color").asText() : null;
        String faction = args.has("faction") ? args.get("faction").asText() : null;
        String narrative = args.has("narrative") ? args.get("narrative").asText() : null;
        String worldview = args.has("worldview") ? args.get("worldview").asText() : null;
        String capital = args.has("capital") ? args.get("capital").asText() : null;
        String ruler = args.has("ruler") ? args.get("ruler").asText() : null;
        String religion = args.has("religion") ? args.get("religion").asText() : null;
        boolean autoGen = !args.has("autoGenerate") || args.get("autoGenerate").asBoolean();
        return toJson(mapService.initNation(worldId, nodeId, name, seedQ, seedR, maxHexes,
                tag, color, faction, narrative, worldview, capital, ruler, religion, autoGen));
    }

    private String handleUpdateTerrainType(JsonNode args) throws IOException {
        String worldId = args.get("worldId").asText();
        String nodeId = args.has("nodeId") ? args.get("nodeId").asText() : "n0000";
        String key = args.get("key").asText();
        String name = args.has("name") ? args.get("name").asText() : null;
        String color = args.has("color") ? args.get("color").asText() : null;
        Integer food = args.has("food") ? args.get("food").asInt() : null;
        Integer gold = args.has("gold") ? args.get("gold").asInt() : null;
        Integer stone = args.has("stone") ? args.get("stone").asInt() : null;
        Integer moveCost = args.has("moveCost") ? args.get("moveCost").asInt() : null;
        String desc = args.has("description") ? args.get("description").asText() : null;
        return toJson(mapService.updateTerrainType(worldId, nodeId, key, name, color, food, gold, stone, moveCost, desc));
    }

    private static String toJson(Object obj) throws IOException {
        return MAPPER.writeValueAsString(obj);
    }
}
