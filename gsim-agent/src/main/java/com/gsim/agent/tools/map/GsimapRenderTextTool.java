package com.gsim.agent.tools.map;

import com.gsim.agentlib.tool.AgentTool.Permission;
import com.gsim.agentlib.tool.ToolCall;
import com.gsim.agentlib.tool.ToolResult;
import com.gsim.map.map.MapData;
import com.gsim.map.service.MapService;
import com.gsim.map.service.TerrainTextRenderer;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * gsimap_render_text — Render a character-grid hex map around a center
 * coordinate.
 *
 * <p>Produces a compact ASCII view using one character per terrain type
 * (mode {@code terrain}), one character per province (mode {@code region}),
 * or a hex tag-presence marker (mode {@code tag}), with staggered rows for
 * the flat-top hex layout.
 *
 * <p>Radius is clamped to 1–10 (larger maps lose readability in text).
 */
public class GsimapRenderTextTool extends AbstractGsimapTool {

    public GsimapRenderTextTool(MapService mapService) {
        super(mapService);
    }

    @Override
    public String name() {
        return "gsimap_render_text";
    }

    @Override
    public String description() {
        return """
            Render a text-based character map of hex terrain around a center point.
            Each terrain type is displayed as a single ASCII character.
            Supports radius 1-10.""";
    }

    @Override
    public ToolResult execute(ToolCall call) {
        String worldId = com.gsim.agentlib.mcp.GsimRequestContext.worldId();
        if (worldId == null) {
            worldId = call.param("worldId");
            if (worldId == null || worldId.isBlank()) {
                return ToolResult.fail(name(), "worldId is required");
            }
        }

        String nodeId = call.param("nodeId");
        if (nodeId == null || nodeId.isBlank()) {
            nodeId = mapService.readActiveNodeId(worldId);
        }

        int cq, cr, radius;
        try {
            cq = Integer.parseInt(call.param("q"));
            cr = Integer.parseInt(call.param("r"));
            radius = Integer.parseInt(call.param("radius"));
        } catch (NumberFormatException e) {
            return ToolResult.fail(name(), "q, r, radius must be valid integers: " + e.getMessage());
        }

        if (radius < 1 || radius > 10) {
            return ToolResult.fail(name(), "radius must be 1–10, got: " + radius);
        }

        MapData map = mapService.resolve(worldId, nodeId);
        if (map == null || map.hexes().isEmpty()) {
            return ToolResult.fail(name(), "No map data for world: " + worldId);
        }

        String mode = call.param("mode");
        if (mode == null || mode.isBlank()) mode = "terrain";

        return switch (mode) {
            case "terrain" -> {
                String textMap = TerrainTextRenderer.render(map, cq, cr, radius);
                String legend = TerrainTextRenderer.legend(map.terrainTypes());

                StringBuilder sb = new StringBuilder();
                sb.append("## Text Map: center (")
                        .append(cq)
                        .append(",")
                        .append(cr)
                        .append("), radius ")
                        .append(radius)
                        .append("\n\n");

                sb.append("### Legend\n\n");
                sb.append(legend);
                sb.append("\n### Map\n\n");
                sb.append("```\n");
                sb.append(textMap);
                if (!textMap.endsWith("\n")) sb.append('\n');
                sb.append("```\n\n");
                sb.append("address: gsimap:hex:")
                        .append(cq)
                        .append("_")
                        .append(cr)
                        .append("\n");

                yield ToolResult.ok(
                        name(),
                        List.of(new ToolResult.Item(
                                "Center (" + cq + "," + cr + "), radius=" + radius,
                                worldId + ":" + nodeId,
                                sb.toString(),
                                1.0)));
            }
            case "region" -> {
                Set<String> tagFilter = parseTagFilter(call.param("tag"));
                TerrainTextRenderer.RegionRenderResult result =
                        TerrainTextRenderer.renderRegionsDetailed(map, cq, cr, radius, tagFilter);
                String legend = TerrainTextRenderer.legendRegions(
                        result.regionCharMap(), map, result.overlapLines().size());

                StringBuilder sb = new StringBuilder();
                sb.append("## Region Map: center (")
                        .append(cq)
                        .append(",")
                        .append(cr)
                        .append("), radius ")
                        .append(radius)
                        .append("\n\n");

                sb.append("### Legend\n\n");
                sb.append(legend);
                sb.append("\n### Map\n\n");
                sb.append("```\n");
                sb.append(result.text());
                if (!result.text().endsWith("\n")) sb.append('\n');
                sb.append("```\n\n");

                if (!result.overlapLines().isEmpty()) {
                    sb.append("### Overlapping hexes\n\n");
                    for (String line : result.overlapLines()) {
                        sb.append(line).append("\n");
                    }
                    sb.append("\n");
                }

                String address = regionAddress(map, cq, cr, result.regionCharMap());
                if (address != null) sb.append(address).append("\n");

                yield ToolResult.ok(
                        name(),
                        List.of(new ToolResult.Item(
                                "Center (" + cq + "," + cr + "), radius=" + radius + ", mode=region",
                                worldId + ":" + nodeId,
                                sb.toString(),
                                1.0)));
            }
            case "tag" -> {
                String tagKey = call.param("tagKey");
                if (tagKey == null || tagKey.isBlank()) {
                    yield ToolResult.fail(name(), "tagKey is required for mode=tag");
                }
                String textMap = TerrainTextRenderer.renderTagPresence(map, cq, cr, radius, tagKey);
                String legend = TerrainTextRenderer.legendTagPresence(tagKey);

                StringBuilder sb = new StringBuilder();
                sb.append("## Tag Map: ")
                        .append(tagKey)
                        .append(" center (")
                        .append(cq)
                        .append(",")
                        .append(cr)
                        .append("), radius ")
                        .append(radius)
                        .append("\n\n");

                sb.append("### Legend\n\n");
                sb.append(legend);
                sb.append("\n### Map\n\n");
                sb.append("```\n");
                sb.append(textMap);
                if (!textMap.endsWith("\n")) sb.append('\n');
                sb.append("```\n");

                yield ToolResult.ok(
                        name(),
                        List.of(new ToolResult.Item(
                                "Center (" + cq + "," + cr + "), radius=" + radius + ", mode=tag",
                                worldId + ":" + nodeId,
                                sb.toString(),
                                1.0)));
            }
            default -> ToolResult.fail(name(), "mode must be terrain, region, or tag, got: " + mode);
        };
    }

    /**
     * Parses the region-mode {@code tag} whitelist parameter: comma-separated
     * province tags, trimmed with empties dropped. Returns null when absent,
     * blank, or yielding no entries (meaning "all non-annexed regions").
     */
    private static Set<String> parseTagFilter(String tagParam) {
        if (tagParam == null || tagParam.isBlank()) return null;
        Set<String> filter = new LinkedHashSet<>();
        for (String part : tagParam.split(",")) {
            String trimmed = part.trim();
            if (!trimmed.isEmpty()) filter.add(trimmed);
        }
        return filter.isEmpty() ? null : filter;
    }

    /**
     * Computes the region-mode address line, using the (name-sorted first)
     * participating province containing the center hex; null when the center
     * hex belongs to no participating province.
     */
    private static String regionAddress(MapData map, int cq, int cr, Map<String, String> regionCharMap) {
        String hexKey = MapData.hexKey(cq, cr);
        String best = null;
        for (var entry : map.provinces().entrySet()) {
            String name = entry.getKey();
            if (!regionCharMap.containsKey(name)) continue;
            if (entry.getValue().hexes().contains(hexKey)) {
                if (best == null || name.compareTo(best) < 0) best = name;
            }
        }
        return best == null ? null : "address: gsimap:region:" + best;
    }

    @Override
    public Map<String, Object> getParameters() {
        Map<String, Object> props = new LinkedHashMap<>();
        props.put("worldId", Map.of("type", "string", "description", "GSim world ID"));
        props.put("nodeId", Map.of("type", "string", "description", "Node ID (optional, defaults to active node)"));
        props.put("q", Map.of("type", "integer", "description", "Axial q coordinate of center"));
        props.put("r", Map.of("type", "integer", "description", "Axial r coordinate of center"));
        props.put(
                "radius",
                Map.of(
                        "type", "integer",
                        "description", "Render radius in hex steps (1-10)"));
        props.put(
                "mode",
                Map.of(
                        "type", "string",
                        "description",
                                "Render mode: terrain (default, existing), region (render provinces grouped by region), tag (render hex tag presence). Default: terrain"));
        props.put(
                "tag",
                Map.of(
                        "type", "string",
                        "description",
                                "Region mode only: comma-separated province tag whitelist (e.g. 王国A,王国B). Empty = all non-annexed regions"));
        props.put(
                "tagKey",
                Map.of(
                        "type", "string",
                        "description", "Tag mode only: the hex tag key to check presence for"));
        return Map.of("type", "object", "properties", props, "required", List.of("worldId", "q", "r", "radius"));
    }

    @Override
    public Permission permission() {
        return Permission.READ;
    }
}
