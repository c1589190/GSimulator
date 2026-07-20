package com.gsimap.tool;

import com.gsim.tool.AgentTool;
import com.gsim.tool.ToolCall;
import com.gsim.tool.ToolResult;
import com.gsim.util.JsonUtils;
import com.gsimap.service.MapService;
import java.util.List;
import java.util.Map;

/**
 * gsimap_delete_region — Delete a region. Auto-saves.
 */
public final class GsimapDeleteRegionTool implements AgentTool {

    private final MapService mapService;

    public GsimapDeleteRegionTool(MapService mapService) {
        this.mapService = mapService;
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
        String worldId = call.param("worldId");
        if (worldId == null || worldId.isBlank()) {
            return ToolResult.fail(name(), "worldId is required");
        }
        String nodeId = call.param("nodeId", "n0000");
        String name = call.param("name");
        if (name == null || name.isBlank()) {
            return ToolResult.fail(name(), "name is required");
        }

        Map<String, Object> result = mapService.deleteRegion(worldId, nodeId, name);
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
}
