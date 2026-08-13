package com.gsimap.tool;

import com.gsim.agentlib.tool.AgentTool.Permission;
import com.gsim.agentlib.tool.ToolCall;
import com.gsim.agentlib.tool.ToolResult;
import com.gsim.core.util.JsonUtils;
import com.gsimap.map.MapResolver;
import com.gsimap.service.MapService;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * gsimap_get_history — Get the map history across all nodes in the chain.
 * Returns per-node snapshots with hex counts and map ownership.
 */
public final class GsimapGetHistoryTool extends AbstractGsimapTool {

    public GsimapGetHistoryTool(MapService mapService) {
        super(mapService);
    }

    @Override
    public String name() {
        return "gsimap_get_history";
    }

    @Override
    public String description() {
        return "Get the map history across all nodes in the chain.";
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

        List<MapResolver.HistoryEntry> history = mapService.history(worldId, nodeId);
        List<Map<String, Object>> entries = new ArrayList<>();
        for (MapResolver.HistoryEntry h : history) {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("nodeId", h.nodeId());
            entry.put("hasOwnMap", h.hasOwnMap());
            entry.put("hexCount", h.map().hexes().size());
            entries.add(entry);
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("worldId", worldId);
        result.put("chain", entries);

        return ToolResult.ok(
                name(), List.of(new ToolResult.Item(worldId, "gsimap_get_history", JsonUtils.toJson(result), 1.0)));
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
                                                "Node ID (optional, defaults to active node)")),
                "required", List.of("worldId"));
    }

    @Override
    public Permission permission() {
        return Permission.READ;
    }
}
