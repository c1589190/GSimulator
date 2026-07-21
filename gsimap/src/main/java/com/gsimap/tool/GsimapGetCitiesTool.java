package com.gsimap.tool;

import com.gsim.tool.AgentTool.Permission;
import com.gsim.tool.ToolCall;
import com.gsim.tool.ToolResult;
import com.gsimap.map.MapData;
import com.gsimap.service.MapService;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * gsimap_get_cities — List all cities on the map with their coordinates.
 *
 * <p>Each city entry includes its name, axial coordinates, and the terrain
 * type of the hex it occupies.
 */
public class GsimapGetCitiesTool extends AbstractGsimapTool {

    public GsimapGetCitiesTool(MapService mapService) {
        super(mapService);
    }

    @Override
    public String name() {
        return "gsimap_get_cities";
    }

    @Override
    public String description() {
        return """
            List all cities on the map with their coordinates.""";
    }

    @Override
    public ToolResult execute(ToolCall call) {
        String worldId = com.gsim.mcp.GsimRequestContext.worldId();
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

        MapData map = mapService.resolve(worldId, nodeId);
        if (map == null || map.hexes().isEmpty()) {
            return ToolResult.fail(name(), "No map data for world: " + worldId);
        }

        if (map.cities() == null || map.cities().isEmpty()) {
            return ToolResult.ok(
                    name(),
                    List.of(new ToolResult.Item(
                            "No cities",
                            worldId + ":" + nodeId,
                            "No cities found on the map for world " + worldId,
                            0.0)));
        }

        StringBuilder sb = new StringBuilder();
        sb.append("## Cities in ").append(worldId).append("\n\n");
        sb.append("Total cities: **").append(map.cities().size()).append("**\n\n");

        for (var entry : map.cities().entrySet()) {
            MapData.City city = entry.getValue();
            String key = MapData.hexKey(city.q(), city.r());
            MapData.HexCell cell = map.hexes().get(key);

            sb.append("### ").append(entry.getKey()).append("\n\n");
            sb.append("- **Name**: ").append(city.name()).append("\n");
            sb.append("- **Coordinates**: (")
                    .append(city.q())
                    .append(",")
                    .append(city.r())
                    .append(")\n");
            if (cell != null) {
                sb.append("- **Terrain**: ").append(cell.terrain()).append("\n");
            }
            if (city.description() != null && !city.description().isBlank()) {
                sb.append("- **Description**: ").append(city.description()).append("\n");
            }
            sb.append("- **Address**: gsimap:city:").append(entry.getKey()).append("\n");
            sb.append("\n");
        }

        sb.append("\n---\nTotal: **").append(map.cities().size()).append("** cities\n");

        return ToolResult.ok(
                name(),
                List.of(new ToolResult.Item(
                        "Cities: " + map.cities().size() + " total", worldId + ":" + nodeId, sb.toString(), 1.0)));
    }

    @Override
    public Map<String, Object> getParameters() {
        Map<String, Object> props = new LinkedHashMap<>();
        props.put("worldId", Map.of("type", "string", "description", "GSim world ID"));
        props.put("nodeId", Map.of("type", "string", "description", "Node ID (optional, defaults to active node)"));
        return Map.of("type", "object", "properties", props, "required", List.of("worldId"));
    }

    @Override
    public Permission permission() {
        return Permission.READ;
    }
}
