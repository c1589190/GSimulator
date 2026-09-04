package com.gsim.agent.tools.map;

import com.gsim.agentsmanager.tool.AgentTool.Permission;
import com.gsim.agentsmanager.tool.ToolCall;
import com.gsim.agentsmanager.tool.ToolResult;
import com.gsim.map.service.MapService;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * gsimap_edge_trace — Trace the connected chains of a pathway type.
 *
 * <p>Reads every edge tagged with the given {@code type} (e.g. "river", "road")
 * and decomposes them into simple connected chains. Each chain is a maximal run
 * of adjacent hexes connected through the type; chains are split at branch
 * points (a hex with 3+ edges of the type) and stop at endpoints (a hex with 1
 * edge, or any non-degree-2 hex). Closed loops are emitted as a chain that
 * ends where it starts.
 */
public class GsimapEdgeTraceTool extends AbstractGsimapTool {

    public GsimapEdgeTraceTool(MapService mapService) {
        super(mapService);
    }

    @Override
    public String name() {
        return "gsimap_edge_trace";
    }

    @Override
    public String description() {
        return """
            Trace the connected chains of a pathway type (e.g. 'river', 'road').
            Returns each chain as a sequence of hexes joined by ->, e.g. 0_1->1_1->2_1.
            Chains are split at branch points (a hex with 3+ edges of the type) and
            stop at endpoints. Parameters: worldId (required), type (required, pathway group id).""";
    }

    @Override
    public ToolResult execute(ToolCall call) {
        String worldId = resolveWorldId(call);
        String type = call.param("type", "").trim();
        if (type.isEmpty()) {
            return ToolResult.fail(name(), "type is required");
        }

        List<List<String>> chains = mapService.tracePathway(worldId, type);

        int edgeCount = chains.stream().mapToInt(c -> Math.max(0, c.size() - 1)).sum();

        StringBuilder sb = new StringBuilder();
        sb.append("## 连通链: type=").append(type).append("\n\n");
        sb.append("- 链数: ")
                .append(chains.size())
                .append(", 边数: ")
                .append(edgeCount)
                .append("\n");

        if (chains.isEmpty()) {
            sb.append("- 未找到该 type 的边。\n");
        } else {
            sb.append("\n");
            for (int i = 0; i < chains.size(); i++) {
                sb.append(i + 1)
                        .append(". ")
                        .append(String.join("->", chains.get(i)))
                        .append("\n");
            }
        }

        return ToolResult.ok(
                name(),
                List.of(new ToolResult.Item(
                        "连通链 type=" + type, worldId + ":" + mapService.readActiveNodeId(worldId), sb.toString(), 1.0)));
    }

    @Override
    public Map<String, Object> getParameters() {
        Map<String, Object> props = new LinkedHashMap<>();
        props.put("type", Map.of("type", "string", "description", "Pathway group id to trace (e.g. 'river', 'road')"));
        return Map.of("type", "object", "properties", props, "required", List.of("type"));
    }

    @Override
    public Permission permission() {
        return Permission.READ;
    }
}
