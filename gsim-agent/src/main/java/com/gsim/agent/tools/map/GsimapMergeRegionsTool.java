package com.gsim.agent.tools.map;

import com.gsim.agentlib.tool.AgentTool.Permission;
import com.gsim.agentlib.tool.ToolCall;
import com.gsim.agentlib.tool.ToolResult;
import com.gsim.docslib.util.JsonUtils;
import com.gsim.map.service.MapService;
import java.util.List;
import java.util.Map;

/**
 * gsimap_merge_regions — Merge two regions: the dominant region absorbs the annexed region.
 * The annexed region keeps all its original data but is marked as annexed,
 * and its hexes are transferred to the dominant region.
 */
public final class GsimapMergeRegionsTool extends AbstractGsimapTool {

    public GsimapMergeRegionsTool(MapService mapService) {
        super(mapService);
    }

    @Override
    public String name() {
        return "gsimap_merge_regions";
    }

    @Override
    public String description() {
        return "Merge two regions: the dominant region absorbs the annexed region. "
                + "The annexed region's hexes are transferred, but its original data is preserved "
                + "and marked as annexed. The annexed region will no longer appear on the map.";
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
        String dominantName = call.param("dominantName");
        if (dominantName == null || dominantName.isBlank()) {
            return ToolResult.fail(name(), "dominantName is required (the region that absorbs the other)");
        }
        String annexedName = call.param("annexedName");
        if (annexedName == null || annexedName.isBlank()) {
            return ToolResult.fail(name(), "annexedName is required (the region being absorbed)");
        }

        Map<String, Object> result = mapService.mergeRegions(worldId, nodeId, dominantName, annexedName);
        return ToolResult.ok(
                name(),
                List.of(new ToolResult.Item(
                        dominantName + " + " + annexedName, "gsimap_merge_regions", JsonUtils.toJson(result), 1.0)));
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
                                "dominantName",
                                        Map.of(
                                                "type",
                                                "string",
                                                "description",
                                                "The region that absorbs the other (keeps its name/description)"),
                                "annexedName",
                                        Map.of(
                                                "type",
                                                "string",
                                                "description",
                                                "The region being absorbed (preserved but marked as annexed)")),
                "required", List.of("worldId", "dominantName", "annexedName"));
    }

    @Override
    public Permission permission() {
        return Permission.WRITE;
    }
}
