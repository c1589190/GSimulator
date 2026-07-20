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
 * gsimap_find_river_path — Find the minimum-cost river path from a source hex
 * to the nearest water or map edge.
 *
 * <p>Uses Dijkstra with terrain moveCost as edge weight. Delegates to
 * {@link MapService#findRiverPath}.
 */
public class GsimapFindRiverPathTool implements AgentTool {

    private final MapService mapService;

    public GsimapFindRiverPathTool(MapService mapService) {
        this.mapService = mapService;
    }

    @Override
    public String name() {
        return "gsimap_find_river_path";
    }

    @Override
    public String description() {
        return """
            Find the minimum-cost river path from a source hex to the nearest water
            or map edge. Uses terrain moveCost as edge weight.""";
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

        int q;
        int r;
        try {
            q = Integer.parseInt(call.param("q"));
            r = Integer.parseInt(call.param("r"));
        } catch (NumberFormatException e) {
            return ToolResult.fail(name(), "q and r must be valid integers: " + e.getMessage());
        }

        List<String> path = mapService.findRiverPath(worldId, nodeId, q, r);

        StringBuilder sb = new StringBuilder();
        sb.append("## River Path from (").append(q).append(",").append(r).append(")\n\n");
        sb.append("- **Path Length**: ").append(path.size()).append(" hexes\n");
        sb.append("- **Source**: (").append(q).append(",").append(r).append(")\n");
        if (!path.isEmpty()) {
            String last = path.get(path.size() - 1);
            int[] lastQr = MapData.parseHexKey(last);
            sb.append("- **Destination**: (")
                    .append(lastQr[0])
                    .append(",")
                    .append(lastQr[1])
                    .append(")\n");
        }

        if (path.isEmpty()) {
            sb.append("\nNo path found to water or map edge.\n");
        } else {
            sb.append("\n### Path Steps\n\n");
            for (int i = 0; i < path.size(); i++) {
                int[] qr = MapData.parseHexKey(path.get(i));
                sb.append(i)
                        .append(". (")
                        .append(qr[0])
                        .append(",")
                        .append(qr[1])
                        .append(")");
                if (i == 0) sb.append(" (start)");
                if (i == path.size() - 1) sb.append(" (water/edge)");
                sb.append("\n");
            }
        }

        sb.append("\n\nsource: gsimap:hex:").append(q).append("_").append(r).append("\n");
        if (!path.isEmpty()) {
            int[] lastQr = MapData.parseHexKey(path.get(path.size() - 1));
            sb.append("destination: gsimap:hex:").append(lastQr[0]).append("_").append(lastQr[1]).append("\n");
        }

        return ToolResult.ok(
                name(),
                List.of(new ToolResult.Item(
                        "River path from (" + q + "," + r + ")", worldId + ":" + nodeId, sb.toString(), 1.0)));
    }

    @Override
    public Map<String, Object> getParameters() {
        Map<String, Object> props = new LinkedHashMap<>();
        props.put("worldId", Map.of("type", "string", "description", "GSim world ID"));
        props.put("nodeId", Map.of("type", "string", "description", "Node ID (optional, defaults to active node)"));
        props.put("q", Map.of("type", "integer", "description", "Source hex axial q coordinate"));
        props.put("r", Map.of("type", "integer", "description", "Source hex axial r coordinate"));
        return Map.of("type", "object", "properties", props, "required", List.of("worldId", "q", "r"));
    }

    @Override
    public Permission permission() {
        return Permission.READ;
    }
}
