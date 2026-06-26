package com.gsim.worldinfo.tool;

import com.gsim.tool.AgentTool;
import com.gsim.tool.ToolCall;
import com.gsim.tool.ToolResult;
import com.gsim.worldinfo.NodeSnapshot;
import com.gsim.worldinfo.WorldInformation;
import com.gsim.worldinfo.loader.ActiveStateManager;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * node_switch — switch the active node to another node in the branch chain.
 *
 * <p>Only allows switching to nodes already loaded in the current chain.
 * To go to a newly created node, a system rebuild is needed.
 */
public final class NodeSwitchTool implements AgentTool {

    private final Supplier<WorldInformation> worldInfo;
    private final Path worldsDir;
    private final Runnable onNodeChanged;

    public NodeSwitchTool(Supplier<WorldInformation> worldInfo, Path worldsDir, Runnable onNodeChanged) {
        this.worldInfo = worldInfo;
        this.worldsDir = worldsDir;
        this.onNodeChanged = onNodeChanged;
    }

    @Override
    public String name() { return "node_switch"; }

    @Override
    public String description() {
        return "Switch the active node to another node in the current branch chain. " +
               "Use node_list to see available nodes. " +
               "Cannot switch to a node outside the current chain. " +
               "To create and switch to a new child node, use node_create instead.";
    }

    @Override
    public ToolResult execute(ToolCall call) {
        String nodeId = call.param("nodeId");
        if (nodeId == null || nodeId.isBlank()) {
            return ToolResult.fail("node_switch", "nodeId is required");
        }

        WorldInformation wi = worldInfo.get();
        if (nodeId.equals(wi.activeNodeId())) {
            return ToolResult.ok("node_switch", List.of(
                new ToolResult.Item(nodeId, nodeId,
                    "Already on node " + nodeId + " (no change)", 1.0)));
        }

        NodeSnapshot target = wi.nodeById(nodeId);
        if (target == null) {
            List<String> available = wi.branchChain().stream()
                .map(NodeSnapshot::nodeId).toList();
            return ToolResult.fail("node_switch",
                "Node '" + nodeId + "' not found in current chain. " +
                "Available nodes: " + available + ". " +
                "Use node_list to see all nodes.");
        }

        String worldId = wi.worldId();
        ActiveStateManager.ActiveState currentState = ActiveStateManager.load(worldsDir, worldId);
        Map<String, String> sessions = currentState != null
            ? currentState.sessions() : new LinkedHashMap<>();
        ActiveStateManager.save(worldsDir, worldId,
            new ActiveStateManager.ActiveState(nodeId, sessions));

        if (onNodeChanged != null) onNodeChanged.run();

        String summary = "Switched to node " + nodeId + " (turn " + target.turn() +
            ", worldTime=" + target.worldTime() + ")";
        return ToolResult.ok("node_switch", List.of(
            new ToolResult.Item(nodeId, nodeId, summary, 1.0)));
    }

    @Override
    public Map<String, Object> getParameters() {
        return Map.of(
            "type", "object",
            "properties", Map.of(
                "nodeId", Map.of("type", "string", "description",
                    "Target node ID, e.g. 'n0002'. Use node_list to see available nodes.")
            ),
            "required", List.of("nodeId")
        );
    }
}
