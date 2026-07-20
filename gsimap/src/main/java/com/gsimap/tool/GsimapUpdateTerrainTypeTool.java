package com.gsimap.tool;

import com.gsim.tool.AgentTool;
import com.gsim.tool.AgentTool.Permission;
import com.gsim.tool.ToolCall;
import com.gsim.tool.ToolResult;
import com.gsim.util.JsonUtils;
import com.gsimap.service.MapService;
import java.util.List;
import java.util.Map;

/**
 * gsimap_update_terrain_type — Update a terrain type definition.
 * Provide only the fields you want to change (name, color, food, gold, stone, moveCost, description).
 */
public final class GsimapUpdateTerrainTypeTool implements AgentTool {

    private final MapService mapService;

    public GsimapUpdateTerrainTypeTool(MapService mapService) {
        this.mapService = mapService;
    }

    @Override
    public String name() {
        return "gsimap_update_terrain_type";
    }

    @Override
    public String description() {
        return "Update a terrain type definition (name, color, food, gold, stone, moveCost, description).";
    }

    @Override
    public ToolResult execute(ToolCall call) {
        String worldId = call.param("worldId");
        if (worldId == null || worldId.isBlank()) {
            return ToolResult.fail(name(), "worldId is required");
        }
        String nodeId = call.param("nodeId", "n0000");
        String key = call.param("key");
        if (key == null || key.isBlank()) {
            return ToolResult.fail(
                    name(), "key is required (water/lowland/hills/plains/mountain/swamp/desert/tundra/forest)");
        }
        String name = call.param("name");
        String color = call.param("color");
        Integer food = parseIntParam(call, "food");
        Integer gold = parseIntParam(call, "gold");
        Integer stone = parseIntParam(call, "stone");
        Integer moveCost = parseIntParam(call, "moveCost");
        String description = call.param("description");

        Map<String, Object> partial = mapService.updateTerrainType(
                worldId, nodeId, key, name, color, food, gold, stone, moveCost, description);
        Map<String, Object> result = new java.util.LinkedHashMap<>(partial);
        result.put("address", "gsimap:terrain:" + key);
        return ToolResult.ok(
                name(), List.of(new ToolResult.Item(key, "gsimap_update_terrain_type", JsonUtils.toJson(result), 1.0)));
    }

    private static Integer parseIntParam(ToolCall call, String key) {
        String val = call.param(key);
        if (val == null || val.isBlank()) return null;
        try {
            return Integer.parseInt(val);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    @Override
    public Map<String, Object> getParameters() {
        return Map.of(
                "type", "object",
                "properties",
                        Map.of(
                                "worldId", Map.of("type", "string", "description", "GSim world ID"),
                                "nodeId",
                                        Map.of(
                                                "type",
                                                "string",
                                                "description",
                                                "Node ID (optional, defaults to n0000)"),
                                "key",
                                        Map.of(
                                                "type",
                                                "string",
                                                "description",
                                                "Terrain key: water, lowland, hills, plains, mountain, swamp, desert, tundra, forest"),
                                "name", Map.of("type", "string", "description", "New display name (e.g. '山区')"),
                                "color", Map.of("type", "string", "description", "New hex color (e.g. '#B8A88A')"),
                                "food", Map.of("type", "integer", "description", "Food output"),
                                "gold", Map.of("type", "integer", "description", "Gold output"),
                                "stone", Map.of("type", "integer", "description", "Stone output"),
                                "moveCost", Map.of("type", "integer", "description", "Movement cost"),
                                "description", Map.of("type", "string", "description", "Tooltip description")),
                "required", List.of("worldId", "key"));
    }

    @Override
    public Permission permission() {
        return Permission.WRITE;
    }
}
