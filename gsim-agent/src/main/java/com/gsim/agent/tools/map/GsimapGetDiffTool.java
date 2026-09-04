package com.gsim.agent.tools.map;

import com.gsim.agentlib.tool.AgentTool.Permission;
import com.gsim.agentlib.tool.ToolCall;
import com.gsim.agentlib.tool.ToolResult;
import com.gsim.docslib.util.JsonUtils;
import com.gsim.map.map.MapDiff;
import com.gsim.map.map.MapStore;
import com.gsim.map.service.MapService;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * gsimap_get_diff — Get the map changes (diff) for a specific node.
 * Shows what hexes changed and which were removed this turn.
 */
public final class GsimapGetDiffTool extends AbstractGsimapTool {

    public GsimapGetDiffTool(MapService mapService) {
        super(mapService);
    }

    @Override
    public String name() {
        return "gsimap_get_diff";
    }

    @Override
    public String description() {
        return "Get the map changes (diff) for a specific node. Shows what changed this turn.";
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
            return ToolResult.fail(name(), "nodeId is required");
        }

        MapDiff diff = MapStore.loadDiff(mapService.getWorldsDir(), worldId, nodeId);
        if (diff == null) {
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("hasDiff", false);
            result.put("nodeId", nodeId);
            return ToolResult.ok(
                    name(), List.of(new ToolResult.Item(nodeId, "gsimap_get_diff", JsonUtils.toJson(result), 1.0)));
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("hasDiff", true);
        result.put("nodeId", nodeId);
        result.put("parentNodeId", diff.parentNodeId());
        result.put("changedCount", diff.changed().size());
        result.put("removedCount", diff.removed().size());
        result.put("changed", diff.changed().keySet());
        result.put("removed", diff.removed());
        result.put(
                "provincesChanged",
                diff.provincesChanged() != null ? diff.provincesChanged().keySet() : List.of());
        result.put("provincesRemoved", diff.provincesRemoved() != null ? diff.provincesRemoved() : List.of());
        result.put(
                "citiesAdded", diff.citiesAdded() != null ? diff.citiesAdded().keySet() : List.of());
        result.put("citiesRemoved", diff.citiesRemoved() != null ? diff.citiesRemoved() : List.of());

        return ToolResult.ok(
                name(), List.of(new ToolResult.Item(nodeId, "gsimap_get_diff", JsonUtils.toJson(result), 1.0)));
    }

    @Override
    public Map<String, Object> getParameters() {
        return Map.of(
                "type", "object",
                "properties",
                        Map.of(
                                "worldId", Map.of("type", "string", "description", "GSim world ID"),
                                "nodeId", Map.of("type", "string", "description", "Node ID (e.g. n0001)")),
                "required", List.of("worldId", "nodeId"));
    }

    @Override
    public Permission permission() {
        return Permission.READ;
    }
}
