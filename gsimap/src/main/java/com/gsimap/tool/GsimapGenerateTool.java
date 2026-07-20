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
 * gsimap_generate — Generate a full terrain hex map for a world using procedural generation.
 * Creates continents with mountain ridges, lowlands, and water.
 * Required before using gsimap_init_nation.
 */
public final class GsimapGenerateTool implements AgentTool {

    private final MapService mapService;

    public GsimapGenerateTool(MapService mapService) {
        this.mapService = mapService;
    }

    @Override
    public String name() {
        return "gsimap_generate";
    }

    @Override
    public String description() {
        return "Generate a full terrain hex map for a world using procedural generation. "
                + "Creates continents with mountain ridges, lowlands, and water. "
                + "Required before using gsimap_init_nation.";
    }

    @Override
    public ToolResult execute(ToolCall call) {
        String worldId = call.param("worldId");
        if (worldId == null || worldId.isBlank()) {
            return ToolResult.fail(name(), "worldId is required");
        }
        String nodeId = call.param("nodeId", "n0000");

        long seed;
        String seedStr = call.param("seed");
        if (seedStr != null && !seedStr.isBlank()) {
            try {
                seed = Long.parseLong(seedStr);
            } catch (NumberFormatException e) {
                seed = System.currentTimeMillis();
            }
        } else {
            seed = System.currentTimeMillis();
        }

        int radius = parseIntParam(call, "radius", 80);
        int ridges = parseIntParam(call, "ridges", 2);
        int fragments = parseIntParam(call, "fragments", 5);
        double landRatio = parseDoubleParam(call, "landRatio", 0.55);
        double coastRoughness = parseDoubleParam(call, "coastRoughness", 0.6);

        Map<String, Object> result =
                mapService.generate(worldId, nodeId, seed, radius, ridges, fragments, landRatio, coastRoughness);
        return ToolResult.ok(
                name(), List.of(new ToolResult.Item(worldId, "gsimap_generate", JsonUtils.toJson(result), 1.0)));
    }

    private static int parseIntParam(ToolCall call, String key, int defaultValue) {
        String val = call.param(key);
        if (val == null || val.isBlank()) return defaultValue;
        try {
            return Integer.parseInt(val);
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    private static double parseDoubleParam(ToolCall call, String key, double defaultValue) {
        String val = call.param(key);
        if (val == null || val.isBlank()) return defaultValue;
        try {
            return Double.parseDouble(val);
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    @Override
    public Map<String, Object> getParameters() {
        return Map.of(
                "type", "object",
                "properties",
                        Map.of(
                                "worldId", Map.of("type", "string", "description", "GSim world ID"),
                                "nodeId", Map.of("type", "string", "description", "Node ID (default: n0000)"),
                                "seed", Map.of("type", "integer", "description", "Random seed (default: current time)"),
                                "radius",
                                        Map.of(
                                                "type",
                                                "integer",
                                                "description",
                                                "Map radius in hex steps (default: 80)"),
                                "ridges",
                                        Map.of(
                                                "type",
                                                "integer",
                                                "description",
                                                "Number of main mountain ridges (default: 2)"),
                                "fragments",
                                        Map.of(
                                                "type",
                                                "integer",
                                                "description",
                                                "Number of fragment ridges (default: 5)"),
                                "landRatio",
                                        Map.of(
                                                "type",
                                                "number",
                                                "description",
                                                "Target land ratio 0..1 (default: 0.55)"),
                                "coastRoughness",
                                        Map.of("type", "number", "description", "Coast roughness 0..1 (default: 0.6)")),
                "required", List.of("worldId"));
    }

    @Override
    public Permission permission() {
        return Permission.WRITE;
    }
}
