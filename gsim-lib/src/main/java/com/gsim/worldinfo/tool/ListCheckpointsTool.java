package com.gsim.worldinfo.tool;

import com.gsim.tool.AgentTool;
import com.gsim.tool.AgentTool.Permission;
import com.gsim.tool.ToolCall;
import com.gsim.tool.ToolResult;
import com.gsim.worldinfo.Checkpoint;
import com.gsim.worldinfo.WorldInformation;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * gsim_list_checkpoints -- List all checkpoints in a GSim node with element counts.
 *
 * <p>Reads the node JSON and returns each checkpoint's name, label, type,
 * and number of elements.
 */
public final class ListCheckpointsTool implements AgentTool {

    private final Supplier<WorldInformation> worldInfo;

    public ListCheckpointsTool(Supplier<WorldInformation> worldInfo) {
        this.worldInfo = worldInfo;
    }

    @Override
    public String name() {
        return "gsim_list_checkpoints";
    }

    @Override
    public String description() {
        return """
            List all checkpoints in a GSim node with element counts.
            Parameters:
              worldId (optional) -- world ID (defaults to active world)
              nodeId (optional) -- node ID (defaults to active node or n0000)
            """;
    }

    @Override
    public Map<String, Object> getParameters() {
        return Map.of(
                "type", "object",
                "properties",
                        Map.of(
                                "worldId",
                                        Map.of(
                                                "type",
                                                "string",
                                                "description",
                                                "World ID (optional, defaults to active world)"),
                                "nodeId",
                                        Map.of(
                                                "type",
                                                "string",
                                                "description",
                                                "Node ID (optional, defaults to active node or n0000)")),
                "required", List.of());
    }

    @Override
    public ToolResult execute(ToolCall call) {
        WorldInformation wi = worldInfo.get();
        if (wi == null) {
            return ToolResult.fail(name(), "No active world information available");
        }

        String worldId = com.gsim.mcp.GsimRequestContext.worldId();
        if (worldId == null) {
            worldId = call.param("worldId");
        }
        if (worldId == null || worldId.isBlank()) {
            worldId = wi.worldId();
        }

        String nodeId = call.param("nodeId");
        if (nodeId == null || nodeId.isBlank()) {
            nodeId = wi.activeNodeId();
        }

        var node = wi.nodeById(nodeId);
        if (node == null) {
            return ToolResult.fail(name(), "Node not found: " + worldId + "/" + nodeId);
        }

        Map<String, Checkpoint> checkpoints = node.checkpoints();
        if (checkpoints == null || checkpoints.isEmpty()) {
            return ToolResult.ok(
                    name(),
                    List.of(new ToolResult.Item(
                            worldId + "/" + nodeId,
                            "checkpoints",
                            "No checkpoints found for " + worldId + "/" + nodeId,
                            1.0)));
        }

        StringBuilder sb = new StringBuilder();
        sb.append("Checkpoints for ").append(worldId).append("/").append(nodeId).append(":\n");
        for (var entry : checkpoints.entrySet()) {
            String cpName = entry.getKey();
            Checkpoint cp = entry.getValue();
            int count = cp.elements() != null ? cp.elements().size() : 0;
            sb.append("  - ")
                    .append(cpName)
                    .append(" (type: ")
                    .append(cp.type() != null ? cp.type() : "misc")
                    .append(", elements: ")
                    .append(count)
                    .append(")\n");
        }

        return ToolResult.ok(
                name(),
                List.of(new ToolResult.Item(
                        worldId + "/" + nodeId + " checkpoints",
                        "checkpoints",
                        sb.toString().trim(),
                        1.0)));
    }

    @Override
    public boolean requiresWorldId() {
        return true;
    }

    @Override
    public Permission permission() {
        return Permission.READ;
    }
}
