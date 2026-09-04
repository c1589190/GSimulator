package com.gsim.agent.tools.map;

import com.gsim.agentsmanager.tool.AgentTool.Permission;
import com.gsim.agentsmanager.tool.ToolCall;
import com.gsim.agentsmanager.tool.ToolResult;
import com.gsim.docslib.util.JsonUtils;
import com.gsim.map.service.MapService;
import java.util.List;
import java.util.Map;

/**
 * gsimap_add_hex_to_region — Add a single hex to a region. Auto-saves after change.
 */
public final class GsimapAddHexToRegionTool extends AbstractGsimapTool {

    public GsimapAddHexToRegionTool(MapService mapService) {
        super(mapService);
    }

    @Override
    public String name() {
        return "gsimap_add_hex_to_region";
    }

    @Override
    public String description() {
        return "Add a single hex to a region. Auto-saves after change.";
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
        String qStr = call.param("q");
        if (qStr == null) {
            return ToolResult.fail(name(), "q (integer) is required");
        }
        String rStr = call.param("r");
        if (rStr == null) {
            return ToolResult.fail(name(), "r (integer) is required");
        }
        int q, r;
        try {
            q = Integer.parseInt(qStr);
            r = Integer.parseInt(rStr);
        } catch (NumberFormatException e) {
            return ToolResult.fail(name(), "q and r must be valid integers");
        }

        Map<String, Object> result = mapService.addHexToRegion(worldId, nodeId, name, q, r);
        result.put("address", "gsimap:region:" + name);
        return ToolResult.ok(
                name(), List.of(new ToolResult.Item(name, "gsimap_add_hex_to_region", JsonUtils.toJson(result), 1.0)));
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
                                "name", Map.of("type", "string", "description", "Region name"),
                                "q", Map.of("type", "integer", "description", "Hex axial q coordinate"),
                                "r", Map.of("type", "integer", "description", "Hex axial r coordinate")),
                "required", List.of("worldId", "nodeId", "name", "q", "r"));
    }

    @Override
    public Permission permission() {
        return Permission.WRITE;
    }
}
