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
               "across all turns. Supports wildcard prefix matching (e.g. 'player.*') " +
               "to return elements from all matching checkpoints. " +
               "Set turnFrom/turnTo to narrow the range.";
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
        int from = turnFromStr != null ? parseInt(turnFromStr, 0) : 0;
        int to = turnToStr != null ? parseInt(turnToStr, Integer.MAX_VALUE) : Integer.MAX_VALUE;

        if (cpId.contains("*")) {
            // wildcard / prefix match
            refs = wi.checkpointHistoryByPrefix(cpId).stream()
                .filter(r -> r.turn() >= from && r.turn() <= to)
                .sorted((a, b) -> {
                    int cmp = a.checkpointId().compareTo(b.checkpointId());
                    if (cmp != 0) return cmp;
                    return Integer.compare(a.turn(), b.turn());
                })
                .toList();
        } else if (turnFromStr != null || turnToStr != null) {
            refs = wi.checkpointHistory(cpId, from, to);
        } else {
            refs = wi.checkpointHistory(cpId);
        }

        String label = "";
        String type = "";
        if (!refs.isEmpty()) {
            // get checkpoint metadata from its source checkpoint
            String lookupCpId = cpId.contains("*") ? refs.get(0).checkpointId() : cpId;
            var firstNode = wi.nodeById(refs.get(0).nodeId());
            if (firstNode != null) {
                Checkpoint cp = firstNode.checkpoints().get(lookupCpId);
                if (cp != null) {
                    label = cp.label();
                    type = cp.type();
                }
            }
        }

        List<ToolResult.Item> items = refs.stream()
            .map(r -> new ToolResult.Item(r.element().key(),
                r.nodeId() + ":" + r.checkpointId() + ":" + r.element().key(),
                r.element().value(), 1.0))
            .toList();

        return ToolResult.ok("query_checkpoint", items);
    }

    @Override
    public Map<String, Object> getParameters() {
        return Map.of(
            "type", "object",
            "properties", Map.of(
                "checkpointId", Map.of("type", "string", "description",
                    "Checkpoint ID like 'player.曹操' or 'worldview'. Supports '*' wildcard for prefix matching, e.g. 'player.*' returns all player.* checkpoints"),
                "turnFrom", Map.of("type", "integer", "description", "Optional start turn (inclusive)"),
                "turnTo", Map.of("type", "integer", "description", "Optional end turn (inclusive)")
            ),
            "required", List.of("checkpointId")
        );
    }

    private static int parseInt(String s, int defaultVal) {
        try { return Integer.parseInt(s); } catch (NumberFormatException e) { return defaultVal; }
    }
}
