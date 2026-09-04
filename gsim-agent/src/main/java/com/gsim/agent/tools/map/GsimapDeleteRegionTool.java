package com.gsim.agent.tools.map;

import com.gsim.agentsmanager.tool.AgentTool.Permission;
import com.gsim.agentsmanager.tool.ToolCall;
import com.gsim.agentsmanager.tool.ToolResult;
import com.gsim.docslib.util.JsonUtils;
import com.gsim.map.service.MapService;
import java.util.List;
import java.util.Map;

/**
 * gsimap_delete_region — Delete a region. Auto-saves.
 */
public final class GsimapDeleteRegionTool extends AbstractGsimapTool {

    public GsimapDeleteRegionTool(MapService mapService) {
        super(mapService);
    }

    @Override
    public String name() {
        return "gsimap_delete_region";
    }

    @Override
    public String description() {
        return "Delete a region. Auto-saves.";
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
            return ToolResult.fail(name(), "nodeId is required — specify the target node explicitly");
        }
        String name = call.param("name");
        if (name == null || name.isBlank()) {
            return ToolResult.fail(name(), "name is required");
        }

        Map<String, Object> result = mapService.deleteRegion(worldId, nodeId, name);
        result.put("address", "gsimap:region:" + name);
        return ToolResult.ok(
                name(), List.of(new ToolResult.Item(name, "gsimap_delete_region", JsonUtils.toJson(result), 1.0)));
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
                                "name", Map.of("type", "string", "description", "Region name to delete")),
                "required", List.of("worldId", "nodeId", "name"));
    }

    @Override
    public Permission permission() {
        return Permission.SYSTEM;
    }
}
