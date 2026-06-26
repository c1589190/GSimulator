package com.gsim.worldinfo.tool;

import com.gsim.tool.AgentTool;
import com.gsim.tool.ToolCall;
import com.gsim.tool.ToolResult;
import com.gsim.worldinfo.Checkpoint;
import com.gsim.worldinfo.ElementRef;
import com.gsim.worldinfo.WorldInformation;

import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * query_checkpoint — return all elements for a given checkpoint across all turns.
 */
public final class QueryCheckpointTool implements AgentTool {

    private final Supplier<WorldInformation> worldInfo;

    public QueryCheckpointTool(Supplier<WorldInformation> worldInfo) {
        this.worldInfo = worldInfo;
    }

    @Override
    public String name() { return "query_checkpoint"; }

    @Override
    public String description() {
        return "Query all historical elements of a checkpoint (player, faction, worldview, etc.) " +
               "across all turns. Set turnFrom/turnTo to narrow the range.";
    }

    @Override
    public ToolResult execute(ToolCall call) {
        String cpId = call.param("checkpointId");
        if (cpId == null || cpId.isBlank()) {
            return ToolResult.fail("query_checkpoint", "checkpointId is required");
        }

        WorldInformation wi = worldInfo.get();
        List<ElementRef> refs;
        String turnFromStr = call.param("turnFrom");
        String turnToStr = call.param("turnTo");
        if (turnFromStr != null || turnToStr != null) {
            int from = turnFromStr != null ? Integer.parseInt(turnFromStr) : 0;
            int to = turnToStr != null ? Integer.parseInt(turnToStr) : Integer.MAX_VALUE;
            refs = wi.checkpointHistory(cpId, from, to);
        } else {
            refs = wi.checkpointHistory(cpId);
        }

        String label = "";
        String type = "";
        if (!refs.isEmpty()) {
            // get checkpoint metadata from its first node
            var firstNode = wi.nodeById(refs.get(0).nodeId());
            if (firstNode != null) {
                Checkpoint cp = firstNode.checkpoints().get(cpId);
                if (cp != null) {
                    label = cp.label();
                    type = cp.type();
                }
            }
        }

        List<ToolResult.Item> items = refs.stream()
            .map(r -> new ToolResult.Item(r.element().key(), r.nodeId() + "@turn" + r.turn(),
                r.element().value(), 1.0))
            .toList();

        return ToolResult.ok("query_checkpoint", items);
    }

    @Override
    public Map<String, Object> getParameters() {
        return Map.of(
            "type", "object",
            "properties", Map.of(
                "checkpointId", Map.of("type", "string", "description", "Checkpoint ID like 'player.曹操' or 'worldview'"),
                "turnFrom", Map.of("type", "integer", "description", "Optional start turn (inclusive)"),
                "turnTo", Map.of("type", "integer", "description", "Optional end turn (inclusive)")
            ),
            "required", List.of("checkpointId")
        );
    }
}
