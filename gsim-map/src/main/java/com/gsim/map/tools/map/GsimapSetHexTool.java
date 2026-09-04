package com.gsim.map.tools.map;

import com.gsim.agentsmanager.tool.ToolCall;
import com.gsim.agentsmanager.tool.ToolResult;
import com.gsim.docslib.util.JsonUtils;
import com.gsim.map.map.MapData;
import com.gsim.map.service.MapService;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * gsimap_set_hex — Set the description and/or tags of hex cells.
 *
 * <p>description is overwritten only when non-null; tags are merged (unmentioned keys preserved).
 * Two modes: single hex via {@code q}/{@code r}, or a batch of hex keys via {@code hexKeys}
 * (applies the same description/tags to all of them in one diff).
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
        return "Set the description and/or key:value tags of hex cells. "
                + "Two modes: single hex — q/r (hex axial coordinates); "
                + "batch — hexKeys (comma-separated hex keys, e.g. 0_0,1_0,2_0; q/r ignored). "
                + "Parameters: worldId (required), nodeId (required, target node to write), "
                + "q/r (optional, hex axial coordinates for single-hex mode), "
                + "hexKeys (optional, comma-separated hex keys for batch mode), "
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
        props.put("q", Map.of("type", "integer", "description", "Hex axial q (single-hex mode)"));
        props.put("r", Map.of("type", "integer", "description", "Hex axial r (single-hex mode)"));
        props.put(
                "hexKeys",
                Map.of(
                        "type",
                        "string",
                        "description",
                        "Comma-separated hex keys (e.g. 0_0,1_0,2_0) to apply the same description/tags to all of them. "
                                + "Optional — when provided, q/r are ignored."));
        props.put("description", Map.of("type", "string", "description", "Optional hex description"));
        props.put("tags", Map.of("type", "object", "description", "Optional key:value tags as JSON object"));
        return Map.of("type", "object", "properties", props, "required", List.of("worldId", "nodeId"));
    }

    @Override
    public ToolResult execute(ToolCall call) {
        String worldId = resolveWorldId(call);
        String nodeId = call.param("nodeId");
        if (nodeId == null || nodeId.isBlank()) {
            return ToolResult.fail(name(), "nodeId is required — specify the target node explicitly");
        }
        String description = call.param("description");
        Map<String, String> tags = parseTags(call.param("tags"));

        String hexKeysRaw = call.param("hexKeys");
        if (hexKeysRaw != null && !hexKeysRaw.trim().isBlank()) {
            return executeBatch(worldId, nodeId, hexKeysRaw, description, tags);
        }

        Integer q = parseHexCoord(call.param("q"));
        Integer r = parseHexCoord(call.param("r"));
        if (q == null || r == null) {
            return ToolResult.fail(
                    name(), "q/r or hexKeys is required — specify a single hex (q,r) or a batch (hexKeys)");
        }

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
                                            cell != null
                                                    ? cell.toString()
                                                    : Map.of().toString())
                                    .toString(),
                            1.0)));
        } catch (IllegalArgumentException e) {
            return ToolResult.fail(name(), e.getMessage());
        }
    }

    private ToolResult executeBatch(
            String worldId, String nodeId, String hexKeysRaw, String description, Map<String, String> tags) {
        Set<String> keys = Arrays.stream(hexKeysRaw.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .collect(Collectors.toCollection(LinkedHashSet::new));
        if (keys.isEmpty()) {
            return ToolResult.fail(name(), "hexKeys is empty");
        }
        try {
            MapData after = mapService.setHexTagsBatch(worldId, nodeId, keys, description, tags);
            List<ToolResult.Item> items = new ArrayList<>();
            for (String key : keys) {
                MapData.HexCell cell = after.hexes().get(key);
                items.add(new ToolResult.Item(
                        key,
                        "gsimap:hex:" + key,
                        Map.of(
                                        "ok",
                                        true,
                                        "hexKey",
                                        key,
                                        "hex",
                                        cell != null
                                                ? cell.toString()
                                                : Map.of().toString())
                                .toString(),
                        1.0));
            }
            return ToolResult.ok(name(), items);
        } catch (IllegalArgumentException e) {
            return ToolResult.fail(name(), e.getMessage());
        }
    }

    private static Integer parseHexCoord(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return Integer.parseInt(raw);
        } catch (NumberFormatException e) {
            return null;
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
