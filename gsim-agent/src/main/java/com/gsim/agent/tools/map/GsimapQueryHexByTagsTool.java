package com.gsim.agent.tools.map;

import com.gsim.agentlib.mcp.GsimRequestContext;
import com.gsim.agentlib.tool.AgentTool.Permission;
import com.gsim.agentlib.tool.ToolCall;
import com.gsim.agentlib.tool.ToolResult;
import com.gsim.core.util.JsonUtils;
import com.gsim.map.map.MapData;
import com.gsim.map.service.MapService;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * gsimap_query_hex_by_tags — 按标签键过滤 hex 的结构化查询工具。
 *
 * <p>按标签键（{@code tagKey}）筛选当前节点地图中的 hex：仅保留 tags 中包含该键的格，
 * 可选通过 {@code valueContains} / {@code valueNotContains} 对标签值做子串包含 / 排除
 * 过滤。命中按 hex key 自然序排序，支持 offset/limit 分页，返回每格完整信息
 * （hexKey、terrain、color、symbol、description、tags）。
 */
public final class GsimapQueryHexByTagsTool extends AbstractGsimapTool {

    public GsimapQueryHexByTagsTool(MapService mapService) {
        super(mapService);
    }

    @Override
    public String name() {
        return "gsimap_query_hex_by_tags";
    }

    @Override
    public String description() {
        return """
            Query hex cells filtered by tag key.
            Returns full info of hexes whose tags contain the given tag key; optionally
            restrict to tag values containing (valueContains) or excluding
            (valueNotContains) a substring. Results are sorted by hex key and paginated.
            Parameters: worldId (required), tagKey (required), valueContains (optional),
            valueNotContains (optional), nodeId (optional), limit (optional, default 20),
            offset (optional, default 0).
            """;
    }

    @Override
    public ToolResult execute(ToolCall call) {
        // worldId: context first, param fallback（与 GsimapQueryByAddressTool 一致）
        String worldId = GsimRequestContext.worldId();
        if (worldId == null) {
            worldId = call.param("worldId");
            if (worldId == null || worldId.isBlank()) {
                return ToolResult.fail(name(), "worldId is required");
            }
        }

        String tagKey = call.param("tagKey");
        if (tagKey == null || tagKey.isBlank()) {
            return ToolResult.fail(name(), "tagKey is required");
        }

        String valueContains = call.param("valueContains");
        String valueNotContains = call.param("valueNotContains");

        String nodeId = call.param("nodeId");
        if (nodeId == null || nodeId.isBlank()) {
            nodeId = mapService.readActiveNodeId(worldId);
        }

        MapData map = mapService.resolve(worldId, nodeId);
        if (map == null) {
            return ToolResult.fail(name(), "No map data for world=" + worldId + " node=" + nodeId);
        }

        // 按 tagKey 过滤：val==null 跳过；valueContains / valueNotContains 子串过滤
        List<Map.Entry<String, MapData.HexCell>> matches = new ArrayList<>();
        for (Map.Entry<String, MapData.HexCell> entry : map.hexes().entrySet()) {
            MapData.HexCell cell = entry.getValue();
            String val = cell.tags().get(tagKey);
            if (val == null) continue;
            if (valueContains != null && !val.contains(valueContains)) continue;
            if (valueNotContains != null && val.contains(valueNotContains)) continue;
            matches.add(entry);
        }

        // 按 hex key 自然序排序后分页
        matches.sort(Map.Entry.comparingByKey());

        int limit = parseInt(call.param("limit"), 20);
        if (limit <= 0) limit = 20;
        int offset = parseInt(call.param("offset"), 0);
        if (offset < 0) offset = 0;

        List<ToolResult.Item> items = new ArrayList<>();
        for (int i = offset; i < matches.size() && items.size() < limit; i++) {
            Map.Entry<String, MapData.HexCell> entry = matches.get(i);
            String hexKey = entry.getKey();
            MapData.HexCell cell = entry.getValue();
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("hexKey", hexKey);
            result.put("terrain", cell.terrain());
            result.put("color", cell.color());
            result.put("symbol", cell.symbol());
            result.put("description", cell.description());
            result.put("tags", cell.tags());
            items.add(new ToolResult.Item(hexKey, "gsimap:hex:" + hexKey, JsonUtils.toJson(result), 1.0));
        }

        return ToolResult.ok(name(), items);
    }

    @Override
    public Map<String, Object> getParameters() {
        return Map.of(
                "type", "object",
                "properties",
                        Map.of(
                                "worldId", Map.of("type", "string", "description", "GSim world ID"),
                                "tagKey", Map.of("type", "string", "description", "Tag key to filter hexes by"),
                                "valueContains",
                                        Map.of(
                                                "type",
                                                "string",
                                                "description",
                                                "If set, only hexes whose tag value contains this substring"),
                                "valueNotContains",
                                        Map.of(
                                                "type",
                                                "string",
                                                "description",
                                                "If set, hexes whose tag value contains this substring are excluded"),
                                "nodeId",
                                        Map.of(
                                                "type",
                                                "string",
                                                "description",
                                                "Target node id (default: active node)"),
                                "limit", Map.of("type", "integer", "description", "Max results (default 20)"),
                                "offset", Map.of("type", "integer", "description", "Pagination offset (default 0)")),
                "required", List.of("worldId", "tagKey"));
    }

    @Override
    public Permission permission() {
        return Permission.READ;
    }

    private static int parseInt(String s, int defaultVal) {
        if (s == null || s.isBlank()) return defaultVal;
        try {
            return Integer.parseInt(s);
        } catch (NumberFormatException e) {
            return defaultVal;
        }
    }
}
