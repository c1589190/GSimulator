package com.gsimap.tool;

import com.gsim.agentlib.tool.AgentTool.Permission;
import com.gsim.agentlib.tool.ToolCall;
import com.gsim.agentlib.tool.ToolResult;
import com.gsimap.map.MapData;
import com.gsimap.service.MapService;
import com.gsimap.service.TerrainTextRenderer;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * gsimap_render_text — Render a character-grid hex map around a center
 * coordinate.
 *
 * <p>Produces a compact ASCII view using one character per terrain type,
 * with staggered rows for the flat-top hex layout. Ideal for quick
 * terrain reconnaissance at a glance.
 *
 * <p>Radius is clamped to 1–10 (larger maps lose readability in text).
 */
public class GsimapRenderTextTool extends AbstractGsimapTool {

    public GsimapRenderTextTool(MapService mapService) {
        super(mapService);
    }

    @Override
    public String name() {
        return "gsimap_render_text";
    }

    @Override
    public String description() {
        return """
            Render a text-based character map of hex terrain around a center point.
            Each terrain type is displayed as a single ASCII character.
            Supports radius 1-10.""";
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

        int cq, cr, radius;
        try {
            cq = Integer.parseInt(call.param("q"));
            cr = Integer.parseInt(call.param("r"));
            radius = Integer.parseInt(call.param("radius"));
        } catch (NumberFormatException e) {
            return ToolResult.fail(name(), "q, r, radius must be valid integers: " + e.getMessage());
        }

        if (radius < 1 || radius > 10) {
            return ToolResult.fail(name(), "radius must be 1–10, got: " + radius);
        }

        MapData map = mapService.resolve(worldId, nodeId);
        if (map == null || map.hexes().isEmpty()) {
            return ToolResult.fail(name(), "No map data for world: " + worldId);
        }

        String textMap = TerrainTextRenderer.render(map, cq, cr, radius);
        String legend = TerrainTextRenderer.legend(map.terrainTypes());

        StringBuilder sb = new StringBuilder();
        sb.append("## Text Map: center (")
                .append(cq)
                .append(",")
                .append(cr)
                .append("), radius ")
                .append(radius)
                .append("\n\n");

        sb.append("### Legend\n\n");
        sb.append(legend);
        sb.append("\n### Map\n\n");
        sb.append("```\n");
        sb.append(textMap);
        if (!textMap.endsWith("\n")) sb.append('\n');
        sb.append("```\n\n");
        sb.append("address: gsimap:hex:").append(cq).append("_").append(cr).append("\n");

        return ToolResult.ok(
                name(),
                List.of(new ToolResult.Item(
                        "Center (" + cq + "," + cr + "), radius=" + radius,
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
        props.put(
                "radius",
                Map.of(
                        "type", "integer",
                        "description", "Render radius in hex steps (1-10)"));
        return Map.of("type", "object", "properties", props, "required", List.of("worldId", "q", "r", "radius"));
    }

    @Override
    public Permission permission() {
        return Permission.READ;
    }
}
