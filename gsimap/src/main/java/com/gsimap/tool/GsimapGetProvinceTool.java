package com.gsimap.tool;

import com.gsim.tool.AgentTool;
import com.gsim.tool.AgentTool.Permission;
import com.gsim.tool.ToolCall;
import com.gsim.tool.ToolResult;
import com.gsimap.map.MapData;
import com.gsimap.service.MapService;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * gsimap_get_province — Query a province by name.
 *
 * <p>Returns all hex cells belonging to it, plus center coordinates, terrain
 * composition, and adjacent region relationships.
 */
public class GsimapGetProvinceTool implements AgentTool {

    private final MapService mapService;

    public GsimapGetProvinceTool(MapService mapService) {
        this.mapService = mapService;
    }

    @Override
    public String name() {
        return "gsimap_get_province";
    }

    @Override
    public String description() {
        return """
            Query a province by name.
            Returns all hex cells belonging to it.""";
    }

    @Override
    public ToolResult execute(ToolCall call) {
        String worldId = call.param("worldId");
        if (worldId == null || worldId.isBlank()) {
            return ToolResult.fail(name(), "worldId is required");
        }

        String nodeId = call.param("nodeId");
        if (nodeId == null || nodeId.isBlank()) {
            nodeId = mapService.readActiveNodeId(worldId);
        }

        String name = call.param("name");
        if (name == null || name.isBlank()) {
            return ToolResult.fail(name(), "name is required");
        }

        MapData map = mapService.resolve(worldId, nodeId);
        if (map == null || map.hexes().isEmpty()) {
            return ToolResult.fail(name(), "No map data for world: " + worldId);
        }

        MapData.Province prov = map.provinces().get(name);
        if (prov == null) {
            return ToolResult.ok(
                    name(),
                    List.of(new ToolResult.Item(
                            "Province not found",
                            worldId + ":" + name,
                            "Province '" + name + "' not found in world " + worldId,
                            0.0)));
        }

        // Compute statistics
        int[] center = MapService.computeCenter(prov.hexes());
        Map<String, Integer> terrainComp = MapService.computeTerrainComposition(prov, map);

        // Build adjacency
        Map<String, Set<String>> regionHexSets = new LinkedHashMap<>();
        for (var entry : map.provinces().entrySet()) {
            regionHexSets.put(entry.getKey(), new HashSet<>(entry.getValue().hexes()));
        }
        Set<String> ownSet = regionHexSets.get(name);
        List<Map<String, Object>> adj = ownSet != null ? MapService.computeAdjacency(ownSet, regionHexSets) : List.of();

        StringBuilder sb = new StringBuilder();
        sb.append("## Province: ").append(name).append("\n\n");
        sb.append("- **Tag**: ").append(prov.tag() != null ? prov.tag() : "").append("\n");
        sb.append("- **Color**: ").append(prov.color()).append("\n");
        sb.append("- **Hex Count**: ").append(prov.hexes().size()).append("\n");
        sb.append("- **Description**: ")
                .append(prov.description() != null ? prov.description() : "")
                .append("\n");
        if (!prov.annexedBy().isBlank()) {
            sb.append("- **Annexed By**: ⚔ ").append(prov.annexedBy()).append(" (不在地图上显示)\n");
        }
        sb.append("- **Center**: (")
                .append(center[0])
                .append(",")
                .append(center[1])
                .append(")\n");

        sb.append("\n### Terrain Composition\n\n");
        if (terrainComp.isEmpty()) {
            sb.append("(no data)\n");
        } else {
            for (var entry : terrainComp.entrySet()) {
                sb.append("- **")
                        .append(entry.getKey())
                        .append("**: ")
                        .append(entry.getValue())
                        .append("\n");
            }
        }

        sb.append("\n### Adjacent Regions\n\n");
        if (adj.isEmpty()) {
            sb.append("(none)\n");
        } else {
            for (var a : adj) {
                sb.append("- **")
                        .append(a.get("name"))
                        .append("** (shared edges: ")
                        .append(a.get("sharedEdges"))
                        .append(")\n");
            }
        }

        boolean detail = "true".equalsIgnoreCase(call.param("detail"));
        if (detail) {
            sb.append("\n### Hex List\n\n");
            for (String key : prov.hexes()) {
                MapData.HexCell cell = map.hexes().get(key);
                int[] qr = MapData.parseHexKey(key);
                sb.append("- (").append(qr[0]).append(",").append(qr[1]).append(")");
                if (cell != null) {
                    sb.append(" — ").append(cell.terrain());
                }
                sb.append("\n");
            }
        } else {
            sb.append("\n(")
                    .append(prov.hexes().size())
                    .append(" hexes omitted — use detail=true for full hex list)\n");
        }

        sb.append("\n\naddress: gsimap:region:").append(name).append("\n");

        return ToolResult.ok(
                name(),
                List.of(new ToolResult.Item(
                        "Province: " + name, worldId + ":" + nodeId + ":" + name, sb.toString(), 1.0)));
    }

    @Override
    public Map<String, Object> getParameters() {
        Map<String, Object> props = new LinkedHashMap<>();
        props.put("worldId", Map.of("type", "string", "description", "GSim world ID"));
        props.put("nodeId", Map.of("type", "string", "description", "Node ID (optional, defaults to active node)"));
        props.put("name", Map.of("type", "string", "description", "Province name"));
        props.put(
                "detail",
                Map.of(
                        "type",
                        "boolean",
                        "description",
                        "Set to true for full hex list (default: summary with stats only)"));
        return Map.of("type", "object", "properties", props, "required", List.of("worldId", "name"));
    }

    @Override
    public Permission permission() {
        return Permission.READ;
    }
}
