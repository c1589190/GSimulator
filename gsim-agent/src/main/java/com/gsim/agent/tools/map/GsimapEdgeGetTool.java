package com.gsim.agent.tools.map;

import com.gsim.agentsmanager.tool.ToolCall;
import com.gsim.agentsmanager.tool.ToolResult;
import com.gsim.map.map.MapData;
import com.gsim.map.service.MapService;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * gsimap_edge_get — Query the pathway tags on a single edge.
 */
public final class GsimapEdgeGetTool extends AbstractGsimapTool {

    public GsimapEdgeGetTool(MapService mapService) {
        super(mapService);
    }

    @Override
    public String name() {
        return "gsimap_edge_get";
    }

    @Override
    public String description() {
        return "Query all pathway tags on the edge between two hexes. "
                + "Returns the edge key, the two hex coordinates, and tag data with defaults merged from PathwayGroup. "
                + "Parameters: worldId (required), q1/r1/q2/r2 (required).";
    }

    @Override
    public Map<String, Object> getParameters() {
        Map<String, Object> props = new LinkedHashMap<>();
        props.put("q1", Map.of("type", "integer", "description", "First hex axial q"));
        props.put("r1", Map.of("type", "integer", "description", "First hex axial r"));
        props.put("q2", Map.of("type", "integer", "description", "Second hex axial q"));
        props.put("r2", Map.of("type", "integer", "description", "Second hex axial r"));
        return Map.of("type", "object", "properties", props, "required", List.of("q1", "r1", "q2", "r2"));
    }

    @Override
    public ToolResult execute(ToolCall call) {
        String worldId = resolveWorldId(call);
        int q1 = Integer.parseInt(call.param("q1"));
        int r1 = Integer.parseInt(call.param("r1"));
        int q2 = Integer.parseInt(call.param("q2"));
        int r2 = Integer.parseInt(call.param("r2"));

        if (q1 == q2 && r1 == r2) return ToolResult.fail(name(), "hexes must be different");

        MapData map = mapService.resolveActive(worldId);
        if (map == null) return ToolResult.fail(name(), "No map data for world: " + worldId);

        String key = MapData.edgeKey(q1, r1, q2, r2);
        Map<String, Map<String, Object>> raw = map.edges().get(key);

        // Merge defaults from PathwayGroup
        Map<String, Map<String, Object>> resolved = MapService.resolveEdgeWithDefaults(raw, map.pathwayGroups());

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("edgeKey", key);
        result.put("hexes", List.of(q1 + "_" + r1, q2 + "_" + r2));
        result.put("tags", resolved != null ? resolved : Map.of());

        return ToolResult.ok(
                name(), List.of(new ToolResult.Item(key, "gsimap_edge_get:" + key, result.toString(), 1.0)));
    }

    @Override
    public Permission permission() {
        return Permission.READ;
    }
}
