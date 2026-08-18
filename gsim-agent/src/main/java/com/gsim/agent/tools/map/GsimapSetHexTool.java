package com.gsim.agent.tools.map;

import com.gsim.agentlib.tool.ToolCall;
import com.gsim.agentlib.tool.ToolResult;
import com.gsim.core.util.JsonUtils;
import com.gsim.map.map.MapData;
import com.gsim.map.service.MapService;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * gsimap_set_hex — Set the description and/or tags of a hex cell.
 *
 * <p>description is overwritten only when non-null; tags are merged (unmentioned keys preserved).
 */
public final class GsimapSetHexTool extends AbstractGsimapTool {

    public GsimapSetHexTool(MapService mapService) {
        super(mapService);
    }

    @Override
    public String name() {
        return "gsimap_set_hex";
    }

    @Override
    public String description() {
        return "Set the description and/or key:value tags of a hex cell. "
                + "Parameters: worldId (required), nodeId (required, target node to write), "
                + "q/r (required, hex axial coordinates), "
                + "description (optional — overwrites only when provided), "
                + "tags (optional, JSON object — merged into existing tags, unmentioned keys are preserved).";
    }

    @Override
    public Map<String, Object> getParameters() {
        Map<String, Object> props = new LinkedHashMap<>();
        props.put("worldId", Map.of("type", "string", "description", "GSim world ID"));
        props.put(
                "nodeId",
                Map.of(
                        "type",
                        "string",
                        "description",
                        "Node ID to write to (required — specify the target node explicitly, e.g. n0000 or the current turn node)"));
        props.put("q", Map.of("type", "integer", "description", "Hex axial q"));
        props.put("r", Map.of("type", "integer", "description", "Hex axial r"));
        props.put("description", Map.of("type", "string", "description", "Optional hex description"));
        props.put("tags", Map.of("type", "object", "description", "Optional key:value tags as JSON object"));
        return Map.of(
                "type", "object", "properties", props, "required", List.of("worldId", "nodeId", "q", "r"));
    }

    @Override
    public ToolResult execute(ToolCall call) {
        String worldId = resolveWorldId(call);
        String nodeId = call.param("nodeId");
        if (nodeId == null || nodeId.isBlank()) {
            return ToolResult.fail(name(), "nodeId is required — specify the target node explicitly");
        }
        int q = Integer.parseInt(call.param("q"));
        int r = Integer.parseInt(call.param("r"));
        String description = call.param("description");
        Map<String, String> tags = parseTags(call.param("tags"));

        try {
            MapData after = mapService.setHexTags(worldId, nodeId, q, r, description, tags);

            String key = MapData.hexKey(q, r);
            MapData.HexCell cell = after.hexes().get(key);
            return ToolResult.ok(
                    name(),
                    List.of(new ToolResult.Item(
                            key,
                            "gsimap:hex:" + key,
                            Map.of(
                                            "ok",
                                            true,
                                            "hexKey",
                                            key,
                                            "hex",
                                            cell != null ? cell.toString() : Map.of().toString())
                                    .toString(),
                            1.0)));
        } catch (IllegalArgumentException e) {
            return ToolResult.fail(name(), e.getMessage());
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, String> parseTags(Object raw) {
        if (raw == null) return Map.of();
        if (raw instanceof Map) {
            Map<String, String> result = new LinkedHashMap<>();
            for (var entry : ((Map<?, ?>) raw).entrySet()) {
                result.put(String.valueOf(entry.getKey()), String.valueOf(entry.getValue()));
            }
            return result;
        }
        if (raw instanceof String s && !s.isBlank()) {
            try {
                Map<?, ?> parsed = JsonUtils.fromJson(s, Map.class);
                if (parsed == null) return Map.of();
                Map<String, String> result = new LinkedHashMap<>();
                for (var entry : parsed.entrySet()) {
                    result.put(String.valueOf(entry.getKey()), String.valueOf(entry.getValue()));
                }
                return result;
            } catch (Exception ignored) {
                return Map.of();
            }
        }
        return Map.of();
    }

    @Override
    public Permission permission() {
        return Permission.WRITE;
    }
}
