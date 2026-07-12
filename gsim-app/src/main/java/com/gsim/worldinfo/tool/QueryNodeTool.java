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
 * query_node — return all checkpoints and elements for a specific turn.
 */
public final class QueryNodeTool implements AgentTool {

    private final Supplier<WorldInformation> worldInfo;

    public QueryNodeTool(Supplier<WorldInformation> worldInfo) {
        this.worldInfo = worldInfo;
    }

    @Override
    public String name() { return "query_node"; }

    @Override
    public String description() {
        return "Query all checkpoints and elements for a specific turn/node. " +
               "Returns the full snapshot of that turn.";
    }

    @Override
    public ToolResult execute(ToolCall call) {
        String nodeId = call.param("nodeId");
        if (nodeId == null || nodeId.isBlank()) {
            return ToolResult.fail("query_node", "nodeId is required");
        }

        WorldInformation wi = worldInfo.get();
        NodeSnapshot node = wi.nodeById(nodeId);
        if (node == null) {
            return ToolResult.fail("query_node", "Unknown node: " + nodeId);
        }

        List<ToolResult.Item> items = new java.util.ArrayList<>();
        for (var entry : node.checkpoints().entrySet()) {
            String cpId = entry.getKey();
            var cp = entry.getValue();
            for (var el : cp.elements()) {
                items.add(new ToolResult.Item(
                    el.key(),
                    nodeId + ":" + cpId + ":" + el.key(),
                    el.value(), 1.0));
            }
        }

        return ToolResult.ok("query_node", items);
    }

    @Override
    public Map<String, Object> getParameters() {
        return Map.of(
            "type", "object",
            "properties", Map.of(
                "nodeId", Map.of("type", "string", "description", "Node ID like 'n0002'")
            ),
            "required", List.of("nodeId")
        );
    }
}
