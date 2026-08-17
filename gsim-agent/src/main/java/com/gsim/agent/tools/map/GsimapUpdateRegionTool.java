package com.gsim.agent.tools.map;

import com.gsim.agentlib.tool.AgentTool.Permission;
import com.gsim.agentlib.tool.ToolCall;
import com.gsim.agentlib.tool.ToolResult;
import com.gsim.core.util.JsonUtils;
import com.gsim.map.service.MapService;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * gsimap_update_region — Update a region's properties (hexes, tag, description, color).
 * Auto-saves after change. Provide only the fields you want to change.
 */
public final class GsimapUpdateRegionTool extends AbstractGsimapTool {

    public GsimapUpdateRegionTool(MapService mapService) {
        super(mapService);
    }

    @Override
    public String name() {
        return "gsimap_update_region";
    }

    @Override
    public String description() {
        return "Update a region's properties (hexes, tag, description, color). Auto-saves after change.";
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
        String name = call.param("name");
        if (name == null || name.isBlank()) {
            return ToolResult.fail(name(), "name is required");
        }
        String tag = call.param("tag");
        String description = call.param("description");
        String color = call.param("color");
        String hexesStr = call.param("hexes");
        List<String> hexes = null;
        if (hexesStr != null && !hexesStr.isBlank()) {
            hexes = new ArrayList<>();
            for (String h : hexesStr.split(",")) {
                String trimmed = h.trim();
                if (!trimmed.isEmpty()) {
                    hexes.add(trimmed);
                }
            }
        }

        Map<String, Object> result =
                new LinkedHashMap<>(mapService.updateRegion(worldId, nodeId, name, tag, description, color, hexes));
        result.put("address", "gsimap:region:" + name);
        return ToolResult.ok(
                name(), List.of(new ToolResult.Item(name, "gsimap_update_region", JsonUtils.toJson(result), 1.0)));
    }

    @Override
    public Map<String, Object> getParameters() {
        return Map.of(
                "type", "object",
                "properties",
                        Map.of(
                                "worldId", Map.of("type", "string", "description", "GSim world ID"),
                                "nodeId",
                                        Map.of(
                                                "type",
                                                "string",
                                                "description",
                                                "Node ID (optional, defaults to active node)"),
                                "name", Map.of("type", "string", "description", "Region name"),
                                "tag", Map.of("type", "string", "description", "New tag (optional)"),
                                "description", Map.of("type", "string", "description", "New description (optional)"),
                                "color",
                                        Map.of(
                                                "type",
                                                "string",
                                                "description",
                                                "New hex color (optional, e.g. '#FF0000')"),
                                "hexes",
                                        Map.of(
                                                "type",
                                                "string",
                                                "description",
                                                "New hex key list as CSV e.g. '10_-5,11_-5' (optional)")),
                "required", List.of("worldId", "name"));
    }

    @Override
    public Permission permission() {
        return Permission.WRITE;
    }
}
