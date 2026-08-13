package com.gsimap.tool;

import com.gsim.agentlib.tool.AgentTool.Permission;
import com.gsim.agentlib.tool.ToolCall;
import com.gsim.agentlib.tool.ToolResult;
import com.gsimap.map.MapData;
import com.gsimap.service.MapService;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * gsimap_get_distance — Calculate hex distance between two points (by
 * coordinates or region names).
 *
 * <p>Axial hex distance is computed using the standard hex grid metric:
 * {@code (|dq| + |dr| + |dq+dr|) / 2}. When region names are provided, the
 * center of each region is used as the coordinate.
 */
public class GsimapGetDistanceTool extends AbstractGsimapTool {

    public GsimapGetDistanceTool(MapService mapService) {
        super(mapService);
    }

    @Override
    public String name() {
        return "gsimap_get_distance";
    }

    @Override
    public String description() {
        return """
            Calculate hex distance between two points (by coordinates or region names).""";
    }

    @Override
    public ToolResult execute(ToolCall call) {
        String worldId = com.gsim.agentlib.mcp.GsimRequestContext.worldId();
        if (worldId == null) {
            worldId = call.param("worldId");
            if (worldId == null || worldId.isBlank()) {
                return ToolResult.fail(name(), "worldId is required");
            }
        }

        String nodeId = call.param("nodeId");
        if (nodeId == null || nodeId.isBlank()) {
            nodeId = mapService.readActiveNodeId(worldId);
        }

        int fromQ;
        int fromR;
        int toQ;
        int toR;
        String fromLabel;
        String toLabel;

        String fromRegion = call.param("fromRegion");
        String toRegion = call.param("toRegion");

        if (fromRegion != null && !fromRegion.isBlank() && toRegion != null && !toRegion.isBlank()) {
            // Region-based distance
            MapData map = mapService.resolve(worldId, nodeId);
            if (map == null || map.provinces().isEmpty()) {
                return ToolResult.fail(name(), "No map data or provinces for world: " + worldId);
            }

            MapData.Province fromP = map.provinces().get(fromRegion);
            MapData.Province toP = map.provinces().get(toRegion);

            if (fromP == null) {
                return ToolResult.fail(name(), "Region not found: " + fromRegion);
            }
            if (toP == null) {
                return ToolResult.fail(name(), "Region not found: " + toRegion);
            }

            int[] fromCenter = MapService.computeCenter(fromP.hexes());
            int[] toCenter = MapService.computeCenter(toP.hexes());
            fromQ = fromCenter[0];
            fromR = fromCenter[1];
            toQ = toCenter[0];
            toR = toCenter[1];
            fromLabel = fromRegion;
            toLabel = toRegion;
        } else {
            // Coordinate-based distance
            try {
                fromQ = parseInt(call.param("fromQ"), "fromQ");
                fromR = parseInt(call.param("fromR"), "fromR");
                toQ = parseInt(call.param("toQ"), "toQ");
                toR = parseInt(call.param("toR"), "toR");
            } catch (IllegalArgumentException e) {
                return ToolResult.fail(name(), e.getMessage());
            }
            fromLabel = "(" + fromQ + "," + fromR + ")";
            toLabel = "(" + toQ + "," + toR + ")";
        }

        int distance = MapService.hexDistance(fromQ, fromR, toQ, toR);

        StringBuilder sb = new StringBuilder();
        sb.append("## Distance Calculation\n\n");
        sb.append("- **From**: ").append(fromLabel).append("\n");
        sb.append("  - Coordinates: (").append(fromQ).append(",").append(fromR).append(")\n");
        sb.append("- **To**: ").append(toLabel).append("\n");
        sb.append("  - Coordinates: (").append(toQ).append(",").append(toR).append(")\n");
        sb.append("- **Hex Distance**: **").append(distance).append("**\n");

        sb.append("\n\nfrom: gsimap:hex:")
                .append(fromQ)
                .append("_")
                .append(fromR)
                .append("\n");
        sb.append("to: gsimap:hex:").append(toQ).append("_").append(toR).append("\n");

        return ToolResult.ok(
                name(),
                List.of(new ToolResult.Item(
                        "Distance: " + fromLabel + " -> " + toLabel + " = " + distance,
                        worldId + ":" + nodeId,
                        sb.toString(),
                        1.0)));
    }

    private int parseInt(String value, String paramName) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(
                    "Provide (fromQ,fromR,toQ,toR) or (fromRegion,toRegion) " + "- missing: " + paramName);
        }
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(paramName + " must be a valid integer: " + e.getMessage());
        }
    }

    @Override
    public Map<String, Object> getParameters() {
        Map<String, Object> props = new LinkedHashMap<>();
        props.put("worldId", Map.of("type", "string", "description", "GSim world ID"));
        props.put("nodeId", Map.of("type", "string", "description", "Node ID (optional, defaults to active node)"));
        props.put(
                "fromQ", Map.of("type", "integer", "description", "Source axial q (required if not using fromRegion)"));
        props.put(
                "fromR", Map.of("type", "integer", "description", "Source axial r (required if not using fromRegion)"));
        props.put("toQ", Map.of("type", "integer", "description", "Target axial q (required if not using toRegion)"));
        props.put("toR", Map.of("type", "integer", "description", "Target axial r (required if not using toRegion)"));
        props.put(
                "fromRegion",
                Map.of("type", "string", "description", "Source region name (alternative to fromQ/fromR)"));
        props.put("toRegion", Map.of("type", "string", "description", "Target region name (alternative to toQ/toR)"));
        return Map.of("type", "object", "properties", props, "required", List.of("worldId"));
    }

    @Override
    public Permission permission() {
        return Permission.READ;
    }
}
