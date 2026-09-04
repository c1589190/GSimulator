package com.gsim.core.tools.worldinfo;

import com.gsim.agentsmanager.config.CoreConfig;
import com.gsim.agentsmanager.tool.AgentTool;
import com.gsim.agentsmanager.tool.AgentTool.Permission;
import com.gsim.agentsmanager.tool.ToolCall;
import com.gsim.agentsmanager.tool.ToolResult;
import com.gsim.core.worldinfo.Checkpoint;
import com.gsim.core.worldinfo.ElementRef;
import com.gsim.core.worldinfo.WorldInformation;
import com.gsim.docslib.doc.DocStore;
import com.gsim.docslib.staging.DocStaging;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * query_checkpoint -- 查询指定检查点在所有回合中的历史元素。
 *
 * <p>返回检查点在整条节点链上（所有回合）的完整历史记录。支持通配符前缀匹配
 * （如 {@code player.*} 可匹配所有 {@code player.} 开头的检查点），
 * 以及通过 turnFrom/turnTo 参数限定查询的回合范围。
 *
 * <p>结果按检查点 ID 和回合号排序，每个元素条目包含原始值。
 *
 * <p>这是 {@link WriteElementTool} 写入数据的对应读取工具。
 */
public final class QueryCheckpointTool implements AgentTool {

    private final Supplier<WorldInformation> worldInfo;
    private final DocStore docStore;
    private final CoreConfig coreConfig;

    public QueryCheckpointTool(Supplier<WorldInformation> worldInfo, DocStore docStore, CoreConfig coreConfig) {
        this.worldInfo = worldInfo;
        this.docStore = docStore;
        this.coreConfig = coreConfig;
    }

    @Override
    public String name() {
        return "query_checkpoint";
    }

    @Override
    public String description() {
        return "Query all historical elements of a checkpoint (player, faction, worldview, etc.) "
                + "across all turns. Supports wildcard prefix matching (e.g. 'player.*') "
                + "to return elements from all matching checkpoints. "
                + "Set turnFrom/turnTo to narrow the range.";
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

        boolean detail = "true".equalsIgnoreCase(call.param("detail"));
        int threshold = coreConfig.getInt(CoreConfig.QUERY_STAGING_THRESHOLD, 3000);
        com.gsim.agentsmanager.QueryScope scope = com.gsim.agentsmanager.QueryScopeContext.get();
        if (scope == null) scope = com.gsim.agentsmanager.QueryScope.none();
        refs = scope.filterRefs(refs);
        List<ToolResult.Item> items = refs.stream()
                .map(r -> {
                    String value = r.element().value();
                    if (!detail && value != null && value.length() > 200) {
                        value = value.substring(0, 200) + "... (truncated, use detail=true for full content)";
                    } else if (detail && value != null && value.length() > threshold && docStore != null) {
                        String ref = r.nodeId() + ":" + r.checkpointId() + ":"
                                + r.element().key();
                        value = DocStaging.stageOrInline(docStore, "wstg_query_", ref, value);
                    }
                    return new ToolResult.Item(
                            r.element().key(),
                            r.nodeId() + ":" + r.checkpointId() + ":"
                                    + r.element().key(),
                            value,
                            1.0);
                })
                .toList();

        return ToolResult.ok("query_checkpoint", items);
    }

    @Override
    public Map<String, Object> getParameters() {
        return Map.of(
                "type", "object",
                "properties",
                        Map.of(
                                "checkpointId",
                                        Map.of(
                                                "type",
                                                "string",
                                                "description",
                                                "Checkpoint ID like 'player.曹操' or 'worldview'. Supports '*' wildcard for prefix matching, e.g. 'player.*' returns all player.* checkpoints"),
                                "turnFrom", Map.of("type", "integer", "description", "Optional start turn (inclusive)"),
                                "turnTo", Map.of("type", "integer", "description", "Optional end turn (inclusive)"),
                                "detail",
                                        Map.of(
                                                "type",
                                                "boolean",
                                                "description",
                                                "Set to true for full element values (default: truncated to 200 chars)")),
                "required", List.of("checkpointId"));
    }

    private static int parseInt(String s, int defaultVal) {
        try {
            return Integer.parseInt(s);
        } catch (NumberFormatException e) {
            return defaultVal;
        }
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
