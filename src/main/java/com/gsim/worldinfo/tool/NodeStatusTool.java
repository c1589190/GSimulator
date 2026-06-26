package com.gsim.worldinfo.tool;

import com.gsim.tool.AgentTool;
import com.gsim.tool.ToolCall;
import com.gsim.tool.ToolResult;
import com.gsim.worldinfo.NodeSnapshot;
import com.gsim.worldinfo.WorldInformation;

import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * node_status — show current active node summary.
 */
public final class NodeStatusTool implements AgentTool {

    private final Supplier<WorldInformation> worldInfo;

    public NodeStatusTool(Supplier<WorldInformation> worldInfo) {
        this.worldInfo = worldInfo;
    }

    @Override
    public String name() { return "node_status"; }

    @Override
    public String description() {
        return "Show the current active node's status: nodeId, turn, worldTime, status, " +
               "parent, chain position, checkpoint ids, and element counts.";
    }

    @Override
    public ToolResult execute(ToolCall call) {
        WorldInformation wi = worldInfo.get();
        NodeSnapshot active = wi.activeNode();

        StringBuilder sb = new StringBuilder();
        sb.append("nodeId: ").append(active.nodeId()).append("\n");
        sb.append("turn: ").append(active.turn()).append("\n");
        sb.append("worldTime: ").append(active.worldTime()).append("\n");
        sb.append("status: ").append(active.status()).append("\n");
        sb.append("parentId: ").append(active.parentId() != null ? active.parentId() : "(root)").append("\n");
        sb.append("chainPosition: ").append(wi.branchChain().indexOf(active) + 1)
          .append("/").append(wi.branchChain().size()).append("\n");
        sb.append("checkpoints:\n");

        if (active.checkpoints().isEmpty()) {
            sb.append("  (none)\n");
        } else {
            for (var entry : active.checkpoints().entrySet()) {
                sb.append("  ").append(entry.getKey())
                  .append("  label=").append(entry.getValue().label())
                  .append("  type=").append(entry.getValue().type())
                  .append("  elements=").append(entry.getValue().elements().size())
                  .append("\n");
            }
        }

        List<ToolResult.Item> items = List.of(
            new ToolResult.Item(active.nodeId(), active.nodeId(), sb.toString().strip(), 1.0));
        return ToolResult.ok("node_status", items);
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
