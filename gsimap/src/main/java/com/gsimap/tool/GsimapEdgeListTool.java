package com.gsimap.tool;

import com.gsim.agentlib.tool.ToolCall;
import com.gsim.agentlib.tool.ToolResult;
import com.gsimap.map.MapData;
import com.gsimap.service.MapService;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * gsimap_edge_list — List edges, optionally filtered by tag, hex, and radius.
 */
public final class GsimapEdgeListTool extends AbstractGsimapTool {

    public GsimapEdgeListTool(MapService mapService) {
        super(mapService);
    }

    @Override
    public String name() {
        return "gsimap_edge_list";
    }

    @Override
    public String description() {
        return "List all edges with pathway tags on the map. "
                + "Optionally filter by tag, center hex, and radius. "
                + "Parameters: worldId (required), tag (optional pathway group id filter), "
                + "q/r/radius (optional center hex and search radius).";
    }

    @Override
    public Map<String, Object> getParameters() {
        Map<String, Object> props = new LinkedHashMap<>();
        props.put("tag", Map.of("type", "string", "description", "Optional pathway group id filter"));
        props.put("q", Map.of("type", "integer", "description", "Optional center q for radius filter"));
        props.put("r", Map.of("type", "integer", "description", "Optional center r for radius filter"));
        props.put("radius", Map.of("type", "integer", "description", "Optional search radius in hex steps"));
        return Map.of("type", "object", "properties", props, "required", List.of());
    }

    @Override
    public ToolResult execute(ToolCall call) {
        String worldId = resolveWorldId(call);
        String tag = call.param("tag", "").trim();
        int cq = Integer.parseInt(call.param("q", "0"));
        int cr = Integer.parseInt(call.param("r", "0"));
        int radius = Integer.parseInt(call.param("radius", "0"));

        MapData map = mapService.resolveActive(worldId);
        if (map == null) return ToolResult.fail(name(), "No map data for world: " + worldId);

        List<ToolResult.Item> items = new ArrayList<>();
        for (var entry : map.edges().entrySet()) {
            String key = entry.getKey();
            Map<String, Map<String, Object>> edgeData = entry.getValue();

            // Filter by tag
            if (!tag.isEmpty() && !edgeData.containsKey(tag)) continue;

            // Filter by radius
            if (radius > 0) {
                int[] coords = MapData.parseEdgeKey(key);
                int d1 = hexDist(cq, cr, coords[0], coords[1]);
                int d2 = hexDist(cq, cr, coords[2], coords[3]);
                if (d1 > radius && d2 > radius) continue;
            }

            // Merge defaults
            Map<String, Map<String, Object>> resolved =
                    MapService.resolveEdgeWithDefaults(edgeData, map.pathwayGroups());

            Map<String, Object> item = new LinkedHashMap<>();
            item.put("edgeKey", key);
            item.put("tags", resolved);
            items.add(new ToolResult.Item(key, "gsimap_edge_list:" + key, item.toString(), 1.0));
        }

        if (items.isEmpty()) {
            return ToolResult.ok(name(), List.of(new ToolResult.Item("无结果", "", "未找到匹配的边", 0)));
        }
        return ToolResult.ok(name(), items);
    }

    private static int hexDist(int q1, int r1, int q2, int r2) {
        int s1 = -(q1 + r1);
        int s2 = -(q2 + r2);
        return (Math.abs(q1 - q2) + Math.abs(r1 - r2) + Math.abs(s1 - s2)) / 2;
    }

    @Override
    public Permission permission() {
        return Permission.READ;
    }
}
