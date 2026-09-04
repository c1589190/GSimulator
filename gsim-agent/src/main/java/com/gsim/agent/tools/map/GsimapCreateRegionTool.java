package com.gsim.agent.tools.map;

import com.gsim.agentlib.tool.AgentTool.Permission;
import com.gsim.agentlib.tool.ToolCall;
import com.gsim.agentlib.tool.ToolResult;
import com.gsim.docslib.util.JsonUtils;
import com.gsim.map.service.MapService;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * gsimap_create_region — Create a new region. Auto-saves.
 */
public final class GsimapCreateRegionTool extends AbstractGsimapTool {

    public GsimapCreateRegionTool(MapService mapService) {
        super(mapService);
    }

    @Override
    public String name() {
        return "gsimap_create_region";
    }

    @Override
    public String description() {
        return "Create a new region. Auto-saves.";
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
            return ToolResult.fail(name(), "nodeId is required — specify the target node explicitly");
        }
        String name = call.param("name");
        if (name == null || name.isBlank()) {
            return ToolResult.fail(name(), "name is required");
        }
        String tag = call.param("tag");
        String color = call.param("color");
        String description = call.param("description");
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

        Map<String, Object> result = mapService.createRegion(worldId, nodeId, name, tag, color, description, hexes);
        result.put("address", "gsimap:region:" + name);
        return ToolResult.ok(
                name(), List.of(new ToolResult.Item(name, "gsimap_create_region", JsonUtils.toJson(result), 1.0)));
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
                                                "Node ID to write to (required — specify the target node explicitly, e.g. n0000 or the current turn node)"),
                                "name", Map.of("type", "string", "description", "New region name"),
                                "tag", Map.of("type", "string", "description", "Tag (optional)"),
                                "color",
                                        Map.of(
                                                "type",
                                                "string",
                                                "description",
                                                "Hex color (optional, default auto-generated)"),
                                "description", Map.of("type", "string", "description", "Description (optional)"),
                                "hexes",
                                        Map.of(
                                                "type",
                                                "string",
                                                "description",
                                                "Initial hex keys as CSV e.g. '10_-5,11_-5' (optional)")),
                "required", List.of("worldId", "nodeId", "name"));
    }

    @Override
    public Permission permission() {
        return Permission.WRITE;
    }
}
