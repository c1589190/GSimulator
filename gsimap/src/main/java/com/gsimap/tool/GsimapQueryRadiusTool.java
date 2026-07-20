package com.gsimap.tool;

import com.gsim.tool.AgentTool;
import com.gsim.tool.AgentTool.Permission;
import com.gsim.tool.ToolCall;
import com.gsim.tool.ToolResult;
import com.gsimap.map.MapData;
import com.gsimap.service.MapService;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * gsimap_query_radius — Query all hex cells within a given radius of a center
 * coordinate.
 *
 * <p>Iterates through hexagonal rings from the center outward, returning all
 * cells that exist on the map.
 */
public class GsimapQueryRadiusTool implements AgentTool {

    private final MapService mapService;

    public GsimapQueryRadiusTool(MapService mapService) {
        this.mapService = mapService;
    }

    @Override
    public String name() {
        return "gsimap_query_radius";
    }

    @Override
    public String description() {
        return """
            Query all hex cells within a given radius of a center coordinate.""";
    }

    @Override
    public ToolResult execute(ToolCall call) {
        String worldId = call.param("worldId");
        if (worldId == null || worldId.isBlank()) {
            return ToolResult.fail(name(), "worldId is required");
        }

        String nodeId = call.param("nodeId");
        if (nodeId == null || nodeId.isBlank()) {
            nodeId = mapService.readActiveNodeId(worldId);
        }

        int cq;
        int cr;
        int radius;
        try {
            cq = Integer.parseInt(call.param("q"));
            cr = Integer.parseInt(call.param("r"));
            radius = Integer.parseInt(call.param("radius"));
        } catch (NumberFormatException e) {
            return ToolResult.fail(name(), "q, r, and radius must be valid integers: " + e.getMessage());
        }

        if (radius < 0) {
            return ToolResult.fail(name(), "radius must be non-negative");
        }

        MapData map = mapService.resolve(worldId, nodeId);
        if (map == null || map.hexes().isEmpty()) {
            return ToolResult.fail(name(), "No map data for world: " + worldId);
        }

        StringBuilder sb = new StringBuilder();
        sb.append("## Radius Query: center (")
                .append(cq)
                .append(",")
                .append(cr)
                .append("), radius ")
                .append(radius)
                .append("\n\n");

        int found = 0;
        StringBuilder terrainSummary = new StringBuilder();
        Map<String, Integer> terrainCounts = new LinkedHashMap<>();

        for (int dq = -radius; dq <= radius; dq++) {
            for (int dr = Math.max(-radius, -dq - radius); dr <= Math.min(radius, -dq + radius); dr++) {
                String key = MapData.hexKey(cq + dq, cr + dr);
                MapData.HexCell cell = map.hexes().get(key);
                if (cell != null) {
                    found++;
                    terrainCounts.merge(cell.terrain(), 1, Integer::sum);
                    terrainSummary
                            .append("- (")
                            .append(cq + dq)
                            .append(",")
                            .append(cr + dr)
                            .append("): **")
                            .append(cell.terrain())
                            .append("**")
                            .append(" ")
                            .append(cell.color())
                            .append("\n");
                }
            }
        }

        sb.append("### Summary\n\n");
        sb.append("- **Hexes found**: ").append(found).append("\n");
        sb.append("- **Total map hexes**: ").append(map.hexes().size()).append("\n");
        sb.append("- **Radius**: ").append(radius).append("\n\n");

        sb.append("### Terrain Distribution\n\n");
        for (var entry : terrainCounts.entrySet()) {
            sb.append("- **")
                    .append(entry.getKey())
                    .append("**: ")
                    .append(entry.getValue())
                    .append(" hexes")
                    .append("\n");
        }

        sb.append("\n### Hex Details\n\n").append(terrainSummary);

        sb.append("\n\naddress: gsimap:hex:").append(cq).append("_").append(cr).append("\n");

        return ToolResult.ok(
                name(),
                List.of(new ToolResult.Item(
                        "Radius " + radius + " from (" + cq + "," + cr + ")",
                        worldId + ":" + nodeId,
                        sb.toString(),
                        1.0)));
    }

    @Override
    public Map<String, Object> getParameters() {
        Map<String, Object> props = new LinkedHashMap<>();
        props.put("worldId", Map.of("type", "string", "description", "GSim world ID"));
        props.put("nodeId", Map.of("type", "string", "description", "Node ID (optional, defaults to active node)"));
        props.put("q", Map.of("type", "integer", "description", "Axial q coordinate of center"));
        props.put("r", Map.of("type", "integer", "description", "Axial r coordinate of center"));
        props.put("radius", Map.of("type", "integer", "description", "Search radius in hex steps"));
        return Map.of("type", "object", "properties", props, "required", List.of("worldId", "q", "r", "radius"));
    }

    @Override
    public Permission permission() {
        return Permission.READ;
    }
}
