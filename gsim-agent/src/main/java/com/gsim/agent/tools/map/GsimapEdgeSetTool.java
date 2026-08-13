package com.gsim.agent.tools.map;

import com.gsim.agentlib.tool.ToolCall;
import com.gsim.agentlib.tool.ToolResult;
import com.gsim.core.util.JsonUtils;
import com.gsim.map.map.MapData;
import com.gsim.map.service.MapService;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * gsimap_edge_set — Set a pathway tag on the edge between two adjacent hexes.
 *
 * <p>If the edge does not exist, it is created automatically.
 * If a tag already exists on the edge, its properties are merged/overwritten.
 */
public final class GsimapEdgeSetTool extends AbstractGsimapTool {

    public GsimapEdgeSetTool(MapService mapService) {
        super(mapService);
    }

    @Override
    public String name() {
        return "gsimap_edge_set";
    }

    @Override
    public String description() {
        return "Set a pathway tag on the edge between two adjacent hexes. "
                + "Creates the edge record if it does not exist. "
                + "Parameters: worldId (required), q1/r1/q2/r2 (required, must be adjacent), "
                + "tag (required, pathway group id), "
                + "props (optional, JSON object of edge properties — defaults from PathwayGroup apply if omitted).";
    }

    @Override
    public Map<String, Object> getParameters() {
        Map<String, Object> props = new LinkedHashMap<>();
        props.put("q1", Map.of("type", "integer", "description", "First hex axial q"));
        props.put("r1", Map.of("type", "integer", "description", "First hex axial r"));
        props.put("q2", Map.of("type", "integer", "description", "Second hex axial q"));
        props.put("r2", Map.of("type", "integer", "description", "Second hex axial r"));
        props.put("tag", Map.of("type", "string", "description", "Pathway group id (e.g. 'river', 'road')"));
        props.put("props", Map.of("type", "object", "description", "Optional edge properties as JSON object"));
        return Map.of("type", "object", "properties", props, "required", List.of("q1", "r1", "q2", "r2", "tag"));
    }

    @Override
    @SuppressWarnings("unchecked")
    public ToolResult execute(ToolCall call) {
        String worldId = resolveWorldId(call);
        String nodeId = call.param("nodeId", "n0000");
        int q1 = Integer.parseInt(call.param("q1"));
        int r1 = Integer.parseInt(call.param("r1"));
        int q2 = Integer.parseInt(call.param("q2"));
        int r2 = Integer.parseInt(call.param("r2"));
        String tag = call.param("tag", "").trim();

        if (tag.isEmpty()) return ToolResult.fail(name(), "tag is required");
        if (q1 == q2 && r1 == r2) return ToolResult.fail(name(), "hexes must be different");

        @SuppressWarnings("unchecked")
        Map<String, Object> edgeProps = parseProps(call.param("props"));

        try {
            MapData after = mapService.setEdgeTag(worldId, nodeId, q1, r1, q2, r2, tag, edgeProps);

            String key = MapData.edgeKey(q1, r1, q2, r2);
            Map<String, Map<String, Object>> edge = after.edges().get(key);
            return ToolResult.ok(
                    name(),
                    List.of(new ToolResult.Item(
                            key,
                            "gsimap_edge_set",
                            Map.of("ok", true, "edgeKey", key, "edge", edge != null ? edge : Map.of())
                                    .toString(),
                            1.0)));
        } catch (IllegalArgumentException e) {
            return ToolResult.fail(name(), e.getMessage());
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> parseProps(Object raw) {
        if (raw == null) return Map.of();
        if (raw instanceof Map) return (Map<String, Object>) raw;
        if (raw instanceof String s && !s.isBlank()) {
            try {
                return JsonUtils.fromJson(s, Map.class);
            } catch (Exception ignored) {
                return Map.of();
            }
        }
        return Map.of();
    }

    @Override
    public Permission permission() {
        return Permission.WRITE;
    }
}
