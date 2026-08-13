package com.gsim.agent.tools.map;

import com.gsim.agentlib.tool.AgentTool.Permission;
import com.gsim.agentlib.tool.ToolCall;
import com.gsim.agentlib.tool.ToolResult;
import com.gsim.core.util.JsonUtils;
import com.gsim.map.service.MapService;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * gsimap_rename_region — Rename a region across all data stores:
 * MapData provinces + all GSim checkpoint references (factions, narrative, map, etc.).
 * Updates keys, tags, and text references. Auto-saves.
 */
public final class GsimapRenameRegionTool extends AbstractGsimapTool {

    public GsimapRenameRegionTool(MapService mapService) {
        super(mapService);
    }

    @Override
    public String name() {
        return "gsimap_rename_region";
    }

    @Override
    public String description() {
        return "Rename a region across all data stores: provinces, checkpoint references, keys, and tags. Auto-saves.";
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
        String oldName = call.param("oldName");
        if (oldName == null || oldName.isBlank()) {
            return ToolResult.fail(name(), "oldName is required");
        }
        String newName = call.param("newName");
        if (newName == null || newName.isBlank()) {
            return ToolResult.fail(name(), "newName is required");
        }

        Map<String, Object> result = new LinkedHashMap<>(mapService.renameRegion(worldId, nodeId, oldName, newName));
        result.put("address", "gsimap:region:" + newName);
        return ToolResult.ok(
                name(), List.of(new ToolResult.Item(newName, "gsimap_rename_region", JsonUtils.toJson(result), 1.0)));
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
                                "oldName", Map.of("type", "string", "description", "Current region name"),
                                "newName", Map.of("type", "string", "description", "New region name")),
                "required", List.of("worldId", "oldName", "newName"));
    }

    @Override
    public Permission permission() {
        return Permission.WRITE;
    }
}
