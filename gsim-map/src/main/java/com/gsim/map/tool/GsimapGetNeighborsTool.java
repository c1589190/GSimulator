package com.gsim.map.tool;

import com.gsim.agentlib.tool.AgentTool.Permission;
import com.gsim.agentlib.tool.ToolCall;
import com.gsim.agentlib.tool.ToolResult;
import com.gsim.map.map.MapData;
import com.gsim.map.service.MapService;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * gsimap_get_neighbors — Get all 6 neighboring hex cells of a given coordinate.
 *
 * <p>Each neighbor is returned with its coordinates, terrain, color, and whether
 * the hex exists on the map.
 */
public class GsimapGetNeighborsTool extends AbstractGsimapTool {

    private static final int[][] HEX_DIRS = {{1, 0}, {1, -1}, {0, -1}, {-1, 0}, {-1, 1}, {0, 1}};
    private static final String[] DIR_NAMES = {"E", "NE", "NW", "W", "SW", "SE"};

    public GsimapGetNeighborsTool(MapService mapService) {
        super(mapService);
    }

    @Override
    public String name() {
        return "gsimap_get_neighbors";
    }

    @Override
    public String description() {
        return """
            Get all 6 neighboring hex cells of a given coordinate.""";
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

        int q;
        int r;
        try {
            q = Integer.parseInt(call.param("q"));
            r = Integer.parseInt(call.param("r"));
        } catch (NumberFormatException e) {
            return ToolResult.fail(name(), "q and r must be valid integers: " + e.getMessage());
        }

        MapData map = mapService.resolve(worldId, nodeId);
        if (map == null) {
            return ToolResult.fail(name(), "No map data for world: " + worldId);
        }

        StringBuilder sb = new StringBuilder();
        sb.append("## Neighbors of (").append(q).append(",").append(r).append(")\n\n");

        for (int i = 0; i < HEX_DIRS.length; i++) {
            int nq = q + HEX_DIRS[i][0];
            int nr = r + HEX_DIRS[i][1];
            String nk = MapData.hexKey(nq, nr);
            MapData.HexCell cell = map.hexes().get(nk);

            sb.append("### ")
                    .append(DIR_NAMES[i])
                    .append(" (")
                    .append(nq)
                    .append(",")
                    .append(nr)
                    .append(")\n\n");
            if (cell == null) {
                sb.append("- **Exists**: false (off map)\n");
            } else {
                sb.append("- **Exists**: true\n");
                sb.append("- **Terrain**: ").append(cell.terrain()).append("\n");
                sb.append("- **Color**: ").append(cell.color()).append("\n");
                if (cell.symbol() != null && !cell.symbol().isBlank()) {
                    sb.append("- **Symbol**: ").append(cell.symbol()).append("\n");
                }
            }
            sb.append("\n");
        }

        sb.append("\n\naddress: gsimap:hex:").append(q).append("_").append(r).append("\n");

        return ToolResult.ok(
                name(),
                List.of(new ToolResult.Item(
                        "Neighbors of (" + q + "," + r + ")",
                        worldId + ":" + nodeId + ":" + MapData.hexKey(q, r),
                        sb.toString(),
                        1.0)));
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
