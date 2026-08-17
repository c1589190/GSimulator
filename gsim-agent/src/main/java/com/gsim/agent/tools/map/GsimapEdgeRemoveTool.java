package com.gsim.agent.tools.map;

import com.gsim.agentlib.tool.ToolCall;
import com.gsim.agentlib.tool.ToolResult;
import com.gsim.map.map.MapData;
import com.gsim.map.service.MapService;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * gsimap_edge_remove — Remove a pathway tag from the edge between two hexes.
 *
 * <p>If the edge has no tags left after removal, the edge record is deleted.
 */
public final class GsimapEdgeRemoveTool extends AbstractGsimapTool {

    public GsimapEdgeRemoveTool(MapService mapService) {
        super(mapService);
    }

    @Override
    public String name() {
        return "gsimap_edge_remove";
    }

    @Override
    public String description() {
        return "Remove a pathway tag from the edge between two hexes. "
                + "If the edge has no tags left, the edge record is deleted entirely. "
                + "Parameters: worldId (required), nodeId (required, target node to write), "
                + "q1/r1/q2/r2 (required), tag (required).";
    }

    @Override
    public Map<String, Object> getParameters() {
        Map<String, Object> props = new LinkedHashMap<>();
        props.put("worldId", Map.of("type", "string", "description", "GSim world ID"));
        props.put(
                "nodeId",
                Map.of(
                        "type",
                        "string",
                        "description",
                        "Node ID to write to (required — specify the target node explicitly, e.g. n0000 or the current turn node)"));
        props.put("q1", Map.of("type", "integer", "description", "First hex axial q"));
        props.put("r1", Map.of("type", "integer", "description", "First hex axial r"));
        props.put("q2", Map.of("type", "integer", "description", "Second hex axial q"));
        props.put("r2", Map.of("type", "integer", "description", "Second hex axial r"));
        props.put("tag", Map.of("type", "string", "description", "Pathway group id to remove"));
        return Map.of(
                "type",
                "object",
                "properties",
                props,
                "required",
                List.of("worldId", "nodeId", "q1", "r1", "q2", "r2", "tag"));
    }

    @Override
    public ToolResult execute(ToolCall call) {
        String worldId = resolveWorldId(call);
        String nodeId = call.param("nodeId");
        if (nodeId == null || nodeId.isBlank()) {
            return ToolResult.fail(name(), "nodeId is required — specify the target node explicitly");
        }
        int q1 = Integer.parseInt(call.param("q1"));
        int r1 = Integer.parseInt(call.param("r1"));
        int q2 = Integer.parseInt(call.param("q2"));
        int r2 = Integer.parseInt(call.param("r2"));
        String tag = call.param("tag", "").trim();

        if (tag.isEmpty()) return ToolResult.fail(name(), "tag is required");
        if (q1 == q2 && r1 == r2) return ToolResult.fail(name(), "hexes must be different");

        try {
            MapData after = mapService.removeEdgeTag(worldId, nodeId, q1, r1, q2, r2, tag);

            String key = MapData.edgeKey(q1, r1, q2, r2);
            Map<String, Map<String, Object>> edge = after.edges().get(key);
            return ToolResult.ok(
                    name(),
                    List.of(new ToolResult.Item(
                            key,
                            "gsimap_edge_remove",
                            Map.of("ok", true, "edgeKey", key, "edge", edge != null ? edge : Map.of(), "removed", tag)
                                    .toString(),
                            1.0)));
        } catch (IllegalArgumentException e) {
            return ToolResult.fail(name(), e.getMessage());
        }
    }

    @Override
    public Permission permission() {
        return Permission.WRITE;
    }
}
