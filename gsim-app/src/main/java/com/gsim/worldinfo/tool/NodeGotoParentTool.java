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
 * node_goto_parent — switch to the parent node in the branch chain.
 */
public final class NodeGotoParentTool implements AgentTool {

    private final Supplier<WorldInformation> worldInfo;
    private final Path worldsDir;
    private final Runnable onNodeChanged;

    public NodeGotoParentTool(Supplier<WorldInformation> worldInfo, Path worldsDir, Runnable onNodeChanged) {
        this.worldInfo = worldInfo;
        this.worldsDir = worldsDir;
        this.onNodeChanged = onNodeChanged;
    }

    @Override
    public String name() { return "node_goto_parent"; }

    @Override
    public String description() {
        return "Switch to the parent of the current active node. " +
               "If already at the root node, returns an error. " +
               "Use node_list or node_status to see parent/child relationships.";
    }

    @Override
    public ToolResult execute(ToolCall call) {
        WorldInformation wi = worldInfo.get();
        NodeSnapshot active = wi.activeNode();

        if (active.isRoot()) {
            return ToolResult.fail("node_goto_parent",
                "Already at root node " + active.nodeId() + " — no parent to go to.");
        }

        String parentId = active.parentId();
        NodeSnapshot parent = wi.nodeById(parentId);
        if (parent == null) {
            return ToolResult.fail("node_goto_parent",
                "Parent node '" + parentId + "' not found in current chain. " +
                "The chain may be incomplete. Try node_list to see available nodes.");
        }

        String worldId = wi.worldId();
        ActiveStateManager.ActiveState currentState = ActiveStateManager.load(worldsDir, worldId);
        Map<String, String> sessions = currentState != null
            ? currentState.sessions() : new LinkedHashMap<>();
        ActiveStateManager.save(worldsDir, worldId,
            new ActiveStateManager.ActiveState(parentId, sessions));

        if (onNodeChanged != null) onNodeChanged.run();

        String summary = "Switched to parent node " + parentId + " (turn " + parent.turn() +
            ", worldTime=" + parent.worldTime() + ")";
        return ToolResult.ok("node_goto_parent", List.of(
            new ToolResult.Item(parentId, parentId, summary, 1.0)));
    }

    @Override
    public Map<String, Object> getParameters() {
        return Map.of(
            "type", "object",
            "properties", Map.of(),
            "required", List.of()
        );
    }
}
