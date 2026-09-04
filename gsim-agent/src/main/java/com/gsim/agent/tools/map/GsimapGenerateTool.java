package com.gsim.agent.tools.map;

import com.gsim.agentlib.tool.AgentTool.Permission;
import com.gsim.agentlib.tool.ToolCall;
import com.gsim.agentlib.tool.ToolResult;
import com.gsim.docslib.util.JsonUtils;
import com.gsim.map.service.MapService;
import java.util.List;
import java.util.Map;

/**
 * gsimap_generate — Generate a full terrain hex map for a world using procedural generation.
 * Creates continents with mountain ridges, lowlands, and water.
 * Required before using gsimap_init_nation.
 */
public final class GsimapGenerateTool extends AbstractGsimapTool {

    public GsimapGenerateTool(MapService mapService) {
        super(mapService);
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
        String worldId = com.gsim.agentlib.mcp.GsimRequestContext.worldId();
        if (worldId == null) {
            worldId = call.param("worldId");
        }
        if (worldId == null || worldId.isBlank()) {
            return ToolResult.fail(name(), "worldId is required");
        }
        String nodeId = call.param("nodeId");
        if (nodeId == null || nodeId.isBlank()) {
            return ToolResult.fail(name(), "nodeId is required — specify the target node explicitly");
        }

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
                                "nodeId",
                                        Map.of(
                                                "type",
                                                "string",
                                                "description",
                                                "Node ID to write to (required — specify the target node explicitly, e.g. n0000 or the current turn node)"),
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
                "required", List.of("worldId", "nodeId"));
    }

    @Override
    public Permission permission() {
        return Permission.WRITE;
    }
}
