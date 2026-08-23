package com.gsim.agent.tools.worldinfo;

import com.gsim.agentlib.tool.AgentTool;
import com.gsim.agentlib.tool.AgentTool.Permission;
import com.gsim.agentlib.tool.ToolCall;
import com.gsim.agentlib.tool.ToolResult;
import com.gsim.core.worldinfo.NodeSnapshot;
import com.gsim.core.worldinfo.WorldInformation;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * node_status -- 显示当前活跃节点的详细状态摘要。
 *
 * <p>返回信息包括：节点 ID、回合号、世界内时间、状态、父节点 ID、
 * 在链中的位置（如"3/5"表示链中第 3 个节点，共 5 个节点）、
 * 以及所有检查点的 ID、label、type 和元素数量。
 *
 * <p>此工具不接受参数，始终查询当前活跃节点。
 */
public final class NodeStatusTool implements AgentTool {

    private final Supplier<WorldInformation> worldInfo;

    public NodeStatusTool(Supplier<WorldInformation> worldInfo) {
        this.worldInfo = worldInfo;
    }

    @Override
    public String name() {
        return "node_status";
    }

    @Override
    public String description() {
        return "Show the current active node's status: nodeId, turn, worldTime, status, "
                + "parent, chain position, checkpoint ids, and element counts.";
    }

    @Override
    public ToolResult execute(ToolCall call) {
        WorldInformation wi = worldInfo.get();
        String nodeId = call.param("nodeId");
        if (nodeId == null || nodeId.isBlank()) {
            return ToolResult.fail(name(), "[NODE_ID_REQUIRED] nodeId is required");
        }
        NodeSnapshot active = wi.nodeById(nodeId);
        if (active == null) {
            return ToolResult.fail(name(), "[NODE_NOT_FOUND] node not found: " + nodeId);
        }

        StringBuilder sb = new StringBuilder();
        sb.append("nodeId: ").append(nodeId).append("\n");
        sb.append("turn: ").append(active.turn()).append("\n");
        sb.append("worldTime: ").append(active.worldTime()).append("\n");
        sb.append("status: ").append(active.status()).append("\n");
        sb.append("parentId: ")
                .append(active.parentId() != null ? active.parentId() : "(root)")
                .append("\n");
        sb.append("chainPosition: ")
                .append(wi.branchChain().indexOf(active) + 1)
                .append("/")
                .append(wi.branchChain().size())
                .append("\n");
        sb.append("checkpoints:\n");

        if (active.checkpoints().isEmpty()) {
            sb.append("  (none)\n");
        } else {
            for (var entry : active.checkpoints().entrySet()) {
                sb.append("  ")
                        .append(entry.getKey())
                        .append("  label=")
                        .append(entry.getValue().label())
                        .append("  type=")
                        .append(entry.getValue().type())
                        .append("  elements=")
                        .append(entry.getValue().elements().size())
                        .append("\n");
            }
        }

        List<ToolResult.Item> items =
                List.of(new ToolResult.Item(nodeId, nodeId, sb.toString().strip(), 1.0));
        return ToolResult.ok("node_status", items);
    }

    @Override
    public Map<String, Object> getParameters() {
        return Map.of(
                "type", "object",
                "properties", Map.of("nodeId", Map.of("type", "string", "description", "Node ID to query status for")),
                "required", List.of("nodeId"));
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
