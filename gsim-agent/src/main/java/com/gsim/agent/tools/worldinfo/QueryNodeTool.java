package com.gsim.agent.tools.worldinfo;

import com.gsim.docslib.staging.DocStaging;
import com.gsim.agentsmanager.tool.AgentTool;
import com.gsim.agentsmanager.tool.AgentTool.Permission;
import com.gsim.agentsmanager.tool.ToolCall;
import com.gsim.agentsmanager.tool.ToolResult;
import com.gsim.core.config.CoreConfig;
import com.gsim.docslib.doc.DocStore;
import com.gsim.core.worldinfo.NodeSnapshot;
import com.gsim.core.worldinfo.WorldInformation;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * query_node -- 查询指定回合/节点的全部检查点和元素。
 *
 * <p>返回指定节点的完整快照，包含该节点下所有检查点及其所有元素。
 * 每个元素条目包含 key、完整值（value）和统一引用格式 {@code nodeId:checkpointId:key}。
 *
 * <p>与 {@link QueryCheckpointTool} 不同，此工具只返回单个节点的数据，
 * 不跨回合追溯历史。
 */
public final class QueryNodeTool implements AgentTool {

    private final Supplier<WorldInformation> worldInfo;
    private final DocStore docStore;
    private final CoreConfig coreConfig;

    public QueryNodeTool(Supplier<WorldInformation> worldInfo, DocStore docStore, CoreConfig coreConfig) {
        this.worldInfo = worldInfo;
        this.docStore = docStore;
        this.coreConfig = coreConfig;
    }

    @Override
    public String name() {
        return "query_node";
    }

    @Override
    public String description() {
        return "Query all checkpoints and elements for a specific turn/node. "
                + "Returns the full snapshot of that turn.";
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

        boolean detail = "true".equalsIgnoreCase(call.param("detail"));
        int threshold = coreConfig.getInt(CoreConfig.QUERY_STAGING_THRESHOLD, 3000);
        com.gsim.agentsmanager.QueryScope scope = com.gsim.agentsmanager.QueryScopeContext.get();
        if (scope == null) scope = com.gsim.agentsmanager.QueryScope.none();
        List<ToolResult.Item> items = new java.util.ArrayList<>();
        for (var entry : node.checkpoints().entrySet()) {
            String cpId = entry.getKey();
            var cp = entry.getValue();
            for (var el : cp.elements()) {
                if (!scope.allows(nodeId, cpId, el.key(), el.tags())) {
                    continue;
                }
                String value = el.value();
                if (!detail && value != null && value.length() > 200) {
                    value = value.substring(0, 200) + "... (truncated, use detail=true for full content)";
                } else if (detail && value != null && value.length() > threshold && docStore != null) {
                    value = DocStaging.stageOrInline(
                            docStore, "wstg_query_", nodeId + ":" + cpId + ":" + el.key(), value);
                }
                items.add(new ToolResult.Item(el.key(), nodeId + ":" + cpId + ":" + el.key(), value, 1.0));
            }
        }

        return ToolResult.ok("query_node", items);
    }

    @Override
    public Map<String, Object> getParameters() {
        return Map.of(
                "type", "object",
                "properties",
                        Map.of(
                                "nodeId", Map.of("type", "string", "description", "Node ID like 'n0002'"),
                                "detail",
                                        Map.of(
                                                "type",
                                                "boolean",
                                                "description",
                                                "Set to true for full element values (default: truncated to 200 chars)")),
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
