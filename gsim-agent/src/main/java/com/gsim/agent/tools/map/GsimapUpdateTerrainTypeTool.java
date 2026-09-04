package com.gsim.agent.tools.map;

import com.gsim.agentsmanager.tool.AgentTool.Permission;
import com.gsim.agentsmanager.tool.ToolCall;
import com.gsim.agentsmanager.tool.ToolResult;
import com.gsim.docslib.util.JsonUtils;
import com.gsim.map.service.MapService;
import java.util.List;
import java.util.Map;

/**
 * gsimap_update_terrain_type — Update a terrain type definition.
 * Provide only the fields you want to change (name, color, food, gold, stone, moveCost, description).
 */
public final class GsimapUpdateTerrainTypeTool extends AbstractGsimapTool {

    public GsimapUpdateTerrainTypeTool(MapService mapService) {
        super(mapService);
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
        String worldId = com.gsim.agentsmanager.mcp.GsimRequestContext.worldId();
        if (worldId == null) {
            worldId = call.param("worldId");
            if (worldId == null || worldId.isBlank()) {
                return ToolResult.fail(name(), "worldId is required");
            }
        }
        String nodeId = call.param("nodeId");
        if (nodeId == null || nodeId.isBlank()) {
            return ToolResult.fail(name(), "nodeId is required — specify the target node explicitly");
        }
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
                                                "Node ID to write to (required — specify the target node explicitly, e.g. n0000 or the current turn node)"),
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
                "required", List.of("worldId", "nodeId", "key"));
    }

    @Override
    public Permission permission() {
        return Permission.WRITE;
    }
}
