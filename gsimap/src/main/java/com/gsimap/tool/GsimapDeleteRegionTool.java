package com.gsimap.tool;

import com.gsim.agentlib.tool.AgentTool.Permission;
import com.gsim.agentlib.tool.ToolCall;
import com.gsim.agentlib.tool.ToolResult;
import com.gsim.core.util.JsonUtils;
import com.gsimap.service.MapService;
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
        String worldId = com.gsim.agentlib.mcp.GsimRequestContext.worldId();
        if (worldId == null) {
            worldId = call.param("worldId");
            if (worldId == null || worldId.isBlank()) {
                return ToolResult.fail(name(), "worldId is required");
            }
        }
        String nodeId = call.param("nodeId", "n0000");
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
                                                "Node ID (optional, defaults to n0000)"),
                                "name", Map.of("type", "string", "description", "Region name to delete")),
                "required", List.of("worldId", "name"));
    }

    @Override
    public Permission permission() {
        return Permission.SYSTEM;
    }
}
