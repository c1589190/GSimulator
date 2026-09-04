package com.gsim.map.tools.map;

import com.gsim.agentsmanager.tool.AgentTool.Permission;
import com.gsim.agentsmanager.tool.ToolCall;
import com.gsim.agentsmanager.tool.ToolResult;
import com.gsim.docslib.util.JsonUtils;
import com.gsim.map.map.MapData;
import com.gsim.map.service.MapService;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * gsimap_query_by_address — Resolve a gsimap:* address to the corresponding map entity.
 *
 * <p>Supported address formats:
 * <ul>
 *   <li>{@code gsimap:region:{name}} — looks up a province by name</li>
 *   <li>{@code gsimap:hex:{q}_{r}} — looks up a single hex cell</li>
 *   <li>{@code gsimap:hex:{q}_{r}:tag:{tag_key}} — 按标签键解析 hex 的单个标签</li>
 *   <li>{@code gsimap:city:{name}} — looks up a city by name</li>
 *   <li>{@code gsimap:terrain:{key}} — looks up terrain type definition</li>
 * </ul>
 *
 * <p>Used by {@code query_address} in gsim-lib for routing gsimap-prefixed addresses.
 */
public final class GsimapQueryByAddressTool extends AbstractGsimapTool {

    public GsimapQueryByAddressTool(MapService mapService) {
        super(mapService);
    }

    @Override
    public String name() {
        return "gsimap_query_by_address";
    }

    @Override
    public String description() {
        return """
            Resolve a gsimap: address to map entity details.
            Supports: gsimap:region:{name}, gsimap:hex:{q}_{r}, gsimap:city:{name},
            gsimap:terrain:{key}.
            Parameters: worldId (required), address (required), nodeId (optional).
            """;
    }

    @Override
    public ToolResult execute(ToolCall call) {
        String worldId = com.gsim.agentsmanager.mcp.GsimRequestContext.worldId();
        if (worldId == null) {
            worldId = call.param("worldId");
            if (worldId == null || worldId.isBlank()) {
                return ToolResult.fail(name(), "worldId is required");
            }
        }

        String address = call.param("address");
        if (address == null || address.isBlank()) {
            return ToolResult.fail(name(), "address is required (gsimap:... format)");
        }

        String nodeId = call.param("nodeId");
        if (nodeId == null || nodeId.isBlank()) {
            nodeId = mapService.readActiveNodeId(worldId);
        }

        MapData map = mapService.resolve(worldId, nodeId);
        if (map == null) {
            return ToolResult.fail(name(), "No map data for world=" + worldId + " node=" + nodeId);
        }

        // Parse gsimap:entityType:entityId
        if (!address.startsWith("gsimap:")) {
            return ToolResult.fail(name(), "Address must start with 'gsimap:' prefix: " + address);
        }

        String rest = address.substring("gsimap:".length());
        int colonIdx = rest.indexOf(':');
        if (colonIdx < 0) {
            return ToolResult.fail(name(), "Invalid gsimap address format: " + address);
        }

        String entityType = rest.substring(0, colonIdx);
        String entityId = rest.substring(colonIdx + 1);

        return switch (entityType) {
            case "region" -> resolveRegion(worldId, nodeId, map, entityId, address);
            case "hex" -> resolveHex(map, entityId, address);
            case "city" -> resolveCity(map, entityId, address);
            case "terrain" -> resolveTerrain(map, entityId, address);
            default -> ToolResult.fail(
                    name(), "Unknown entity type: " + entityType + ". Valid types: region, hex, city, terrain");
        };
    }

    private ToolResult resolveRegion(String worldId, String nodeId, MapData map, String name, String address) {
        MapData.Province province = map.provinces().get(name);
        if (province == null) {
            return ToolResult.fail(name(), "Region not found: " + name);
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("address", address);
        result.put("entityType", "region");
        result.put("name", name);
        result.put("tag", province.tag());
        result.put("color", province.color());
        result.put("hexCount", province.hexes().size());
        result.put("hexes", province.hexes());
        return ToolResult.ok(
                name(), List.of(new ToolResult.Item(name, "gsimap:region:" + name, JsonUtils.toJson(result), 1.0)));
    }

    private ToolResult resolveHex(MapData map, String hexKey, String address) {
        // entityId 可能是 "q_r"（整格）或 "q_r:tag:<tagKey>"（单标签）——按第一个冒号切分
        int colon = hexKey.indexOf(':');
        String cellKey = hexKey;
        String rest = null;
        if (colon >= 0) {
            cellKey = hexKey.substring(0, colon);
            rest = hexKey.substring(colon + 1);
        }
        MapData.HexCell cell = map.hexes().get(cellKey);
        if (cell == null) {
            return ToolResult.fail(name(), "Hex not found: " + cellKey);
        }

        String tagKey = null;
        String tagValue = null;
        String itemPath = "gsimap:hex:" + cellKey;
        if (rest != null) {
            if (!rest.startsWith("tag:") || rest.substring(4).isBlank()) {
                return ToolResult.fail(name(), "Invalid hex tag address: gsimap:hex:" + hexKey);
            }
            tagKey = rest.substring(4);
            tagValue = cell.tags().get(tagKey);
            if (tagValue == null) {
                return ToolResult.fail(name(), "Tag not found: " + tagKey + " on hex " + cellKey);
            }
            itemPath = "gsimap:hex:" + cellKey + ":tag:" + tagKey;
        }

        // Find which province owns this hex
        String owner = null;
        for (var entry : map.provinces().entrySet()) {
            if (entry.getValue().hexes().contains(cellKey)) {
                owner = entry.getKey();
                break;
            }
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("address", address);
        result.put("entityType", "hex");
        result.put("hexKey", cellKey);
        result.put("terrain", cell.terrain());
        result.put("color", cell.color());
        result.put("symbol", cell.symbol());
        if (owner != null) result.put("province", owner);
        if (tagKey != null) {
            result.put("tagKey", tagKey);
            result.put("tagValue", tagValue);
        }
        return ToolResult.ok(name(), List.of(new ToolResult.Item(cellKey, itemPath, JsonUtils.toJson(result), 1.0)));
    }

    private ToolResult resolveCity(MapData map, String name, String address) {
        MapData.City city = map.cities().get(name);
        if (city == null) {
            return ToolResult.fail(name(), "City not found: " + name);
        }
        // Find which province owns the city's hex
        String hexKey = MapData.hexKey(city.q(), city.r());
        String owner = null;
        for (var entry : map.provinces().entrySet()) {
            if (entry.getValue().hexes().contains(hexKey)) {
                owner = entry.getKey();
                break;
            }
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("address", address);
        result.put("entityType", "city");
        result.put("name", name);
        result.put("q", city.q());
        result.put("r", city.r());
        if (owner != null) result.put("province", owner);
        result.put("description", city.description());
        return ToolResult.ok(
                name(), List.of(new ToolResult.Item(name, "gsimap:city:" + name, JsonUtils.toJson(result), 1.0)));
    }

    private ToolResult resolveTerrain(MapData map, String key, String address) {
        MapData.TerrainType terrain = map.terrainTypes().get(key);
        if (terrain == null) {
            return ToolResult.fail(name(), "Terrain type not found: " + key);
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("address", address);
        result.put("entityType", "terrain");
        result.put("key", key);
        result.put("name", terrain.name());
        result.put("color", terrain.color());
        result.put("food", terrain.food());
        result.put("gold", terrain.gold());
        result.put("stone", terrain.stone());
        result.put("moveCost", terrain.moveCost());
        return ToolResult.ok(
                name(), List.of(new ToolResult.Item(key, "gsimap:terrain:" + key, JsonUtils.toJson(result), 1.0)));
    }

    @Override
    public Map<String, Object> getParameters() {
        return Map.of(
                "type", "object",
                "properties",
                        Map.of(
                                "worldId", Map.of("type", "string", "description", "GSim world ID"),
                                "address",
                                        Map.of(
                                                "type",
                                                "string",
                                                "description",
                                                "Address in gsimap:entityType:entityId format"),
                                "nodeId",
                                        Map.of(
                                                "type",
                                                "string",
                                                "description",
                                                "Node ID (optional, defaults to active node)")),
                "required", List.of("worldId", "address"));
    }

    @Override
    public Permission permission() {
        return Permission.READ;
    }
}
