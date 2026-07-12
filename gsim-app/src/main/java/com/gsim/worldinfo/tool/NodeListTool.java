package com.gsim.worldinfo.tool;

import com.gsim.tool.AgentTool;
import com.gsim.tool.ToolCall;
import com.gsim.tool.ToolResult;
import com.gsim.worldinfo.NodeSnapshot;
import com.gsim.worldinfo.WorldInformation;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * node_list — list all nodes in the current branch chain.
 *
 * <p>Supports flat (default) and tree modes.
 * In tree mode, shows indented hierarchy.
 */
public final class NodeListTool implements AgentTool {

    private final Supplier<WorldInformation> worldInfo;

    public NodeListTool(Supplier<WorldInformation> worldInfo) {
        this.worldInfo = worldInfo;
    }

    @Override
    public String name() { return "node_list"; }

    @Override
    public String description() {
        return "List all nodes in the current branch chain. " +
               "Use mode='flat' for a simple list or mode='tree' for indented hierarchy. " +
               "Returns each node's id, turn, worldTime, status, and checkpoint count.";
    }

    @Override
    public ToolResult execute(ToolCall call) {
        String mode = call.param("mode");
        WorldInformation wi = worldInfo.get();
        String activeId = wi.activeNodeId();

        List<ToolResult.Item> items = new ArrayList<>();
        List<NodeSnapshot> chain = wi.branchChain();

        if ("tree".equalsIgnoreCase(mode)) {
            // Build a tree representation: indent by depth
            // The chain is already root→active, so depth = index
            StringBuilder tree = new StringBuilder();
            for (int i = 0; i < chain.size(); i++) {
                NodeSnapshot n = chain.get(i);
                String indent = "  ".repeat(i);
                String marker = n.nodeId().equals(activeId) ? " ← active" : "";
                tree.append(indent)
                    .append(n.nodeId())
                    .append(" [t").append(n.turn()).append("] ")
                    .append(n.worldTime())
                    .append("  status=").append(n.status())
                    .append("  cps=").append(n.checkpoints().size())
                    .append(marker)
                    .append("\n");
            }
            items.add(new ToolResult.Item("tree", activeId, tree.toString().strip(), 1.0));
        } else {
            // flat mode
            for (NodeSnapshot n : chain) {
                String marker = n.nodeId().equals(activeId) ? " ← active" : "";
                String summary = "[t%d] %s  status=%s  cps=%d%s".formatted(
                    n.turn(), n.worldTime(), n.status(),
                    n.checkpoints().size(), marker);
                items.add(new ToolResult.Item(n.nodeId(), n.nodeId(), summary, 1.0));
            }
        }

        return ToolResult.ok("node_list", items);
    }

    @Override
    public Map<String, Object> getParameters() {
        return Map.of(
            "type", "object",
            "properties", Map.of(
                "mode", Map.of("type", "string", "description",
                    "'flat' (default) or 'tree' for indented hierarchy")
            ),
            "required", List.of()
        );
    }
}
