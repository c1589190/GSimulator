package com.gsim.agent.tools.map;

import com.gsim.agentsmanager.tool.AgentTool.Permission;
import com.gsim.agentsmanager.tool.ToolCall;
import com.gsim.agentsmanager.tool.ToolResult;
import com.gsim.map.map.MapData;
import com.gsim.map.service.MapService;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * gsimap_get_hex — Query a single hex cell by axial coordinates.
 *
 * <p>Returns color, terrain type, symbol, province ownership, and terrain resource
 * yields for the hex at (q, r).
 */
public class GsimapGetHexTool extends AbstractGsimapTool {

    public GsimapGetHexTool(MapService mapService) {
        super(mapService);
    }

    @Override
    public String name() {
        return "gsimap_get_hex";
    }

    @Override
    public String description() {
        return """
            Query a single hex cell by coordinates.
            Returns color, terrain type, symbol, and province ownership.""";
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
            nodeId = mapService.readActiveNodeId(worldId);
        }

        int q;
        int r;
        try {
            q = Integer.parseInt(call.param("q"));
            r = Integer.parseInt(call.param("r"));
        } catch (NumberFormatException e) {
            return ToolResult.fail(name(), "q and r must be valid integers: " + e.getMessage());
        }

        MapData map = mapService.resolve(worldId, nodeId);
        if (map == null || map.hexes().isEmpty()) {
            return ToolResult.fail(name(), "No map data for world: " + worldId);
        }

        String key = MapData.hexKey(q, r);
        MapData.HexCell cell = map.hexes().get(key);
        if (cell == null) {
            return ToolResult.ok(
                    name(),
                    List.of(new ToolResult.Item(
                            "Hex not found",
                            String.format("(%d,%d)", q, r),
                            "No hex exists at coordinates (" + q + "," + r + ") in world " + worldId,
                            0.0)));
        }

        // Find owning province
        String province = null;
        for (var entry : map.provinces().entrySet()) {
            if (entry.getValue().hexes().contains(key)) {
                province = entry.getKey();
                break;
            }
        }

        StringBuilder sb = new StringBuilder();
        sb.append("## Hex (").append(q).append(",").append(r).append(")\n\n");
        sb.append("- **Terrain**: ").append(cell.terrain()).append("\n");
        sb.append("- **Color**: ").append(cell.color()).append("\n");
        if (cell.symbol() != null && !cell.symbol().isBlank()) {
            sb.append("- **Symbol**: ").append(cell.symbol()).append("\n");
        }
        if (cell.description() != null && !cell.description().isBlank()) {
            sb.append("- **Description**: ").append(cell.description()).append("\n");
        }
        sb.append("- **River Mask**: ").append(cell.riverMask()).append("\n");
        if (province != null) {
            sb.append("- **Province**: ").append(province).append("\n");
        }

        // Include terrain type resource yields
        if (map.terrainTypes() != null) {
            MapData.TerrainType tt = map.terrainTypes().get(cell.terrain());
            if (tt != null) {
                sb.append("\n### Terrain Properties\n\n");
                sb.append("- **Name**: ").append(tt.name()).append("\n");
                sb.append("- **Food**: ").append(tt.food()).append("\n");
                sb.append("- **Gold**: ").append(tt.gold()).append("\n");
                sb.append("- **Stone**: ").append(tt.stone()).append("\n");
                sb.append("- **Move Cost**: ").append(tt.moveCost()).append("\n");
                if (tt.description() != null && !tt.description().isBlank()) {
                    sb.append("- **Description**: ").append(tt.description()).append("\n");
                }
            }
        }

        sb.append("\n\naddress: gsimap:hex:").append(q).append("_").append(r).append("\n");

        return ToolResult.ok(
                name(),
                List.of(new ToolResult.Item(
                        "Hex (" + q + "," + r + ")", worldId + ":" + nodeId + ":" + key, sb.toString(), 1.0)));
    }

    @Override
    public Map<String, Object> getParameters() {
        Map<String, Object> props = new LinkedHashMap<>();
        props.put("worldId", Map.of("type", "string", "description", "GSim world ID"));
        props.put("nodeId", Map.of("type", "string", "description", "Node ID (optional, defaults to active node)"));
        props.put("q", Map.of("type", "integer", "description", "Axial q coordinate"));
        props.put("r", Map.of("type", "integer", "description", "Axial r coordinate"));
        return Map.of("type", "object", "properties", props, "required", List.of("worldId", "q", "r"));
    }

    @Override
    public Permission permission() {
        return Permission.READ;
    }
}
