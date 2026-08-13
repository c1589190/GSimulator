package com.gsimap.tool;

import com.gsim.agentlib.tool.AgentTool.Permission;
import com.gsim.agentlib.tool.ToolCall;
import com.gsim.agentlib.tool.ToolResult;
import com.gsimap.map.MapData;
import com.gsimap.service.MapService;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * gsimap_list_regions — List all regions with center coordinates, terrain
 * composition, and adjacent region relationships.
 *
 * <p>Iterates all provinces in the resolved map and computes metadata for each.
 */
public class GsimapListRegionsTool extends AbstractGsimapTool {

    public GsimapListRegionsTool(MapService mapService) {
        super(mapService);
    }

    @Override
    public String name() {
        return "gsimap_list_regions";
    }

    @Override
    public String description() {
        return """
            List all regions with center coordinates, terrain composition,
            and adjacent region relationships.""";
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

        MapData map = mapService.resolve(worldId, nodeId);
        if (map == null || map.hexes().isEmpty()) {
            return ToolResult.fail(name(), "No map data for world: " + worldId);
        }

        if (map.provinces() == null || map.provinces().isEmpty()) {
            return ToolResult.ok(
                    name(),
                    List.of(new ToolResult.Item(
                            "No regions",
                            worldId + ":" + nodeId,
                            "No regions (provinces) found on the map for world " + worldId,
                            0.0)));
        }

        // Build region hex sets for adjacency computation
        Map<String, Set<String>> regionHexSets = new LinkedHashMap<>();
        for (var entry : map.provinces().entrySet()) {
            regionHexSets.put(entry.getKey(), new HashSet<>(entry.getValue().hexes()));
        }

        boolean detail = "true".equalsIgnoreCase(call.param("detail"));
        StringBuilder sb = new StringBuilder();
        sb.append("## Regions in ").append(worldId).append("\n\n");
        sb.append("Total regions: **").append(map.provinces().size()).append("**\n\n");

        int index = 1;
        for (var entry : map.provinces().entrySet()) {
            String name = entry.getKey();
            MapData.Province prov = entry.getValue();
            Set<String> hexSet = regionHexSets.get(name);

            int[] center = MapService.computeCenter(prov.hexes());
            Map<String, Integer> terrainComp = MapService.computeTerrainComposition(prov, map);
            List<Map<String, Object>> adj = MapService.computeAdjacency(hexSet, regionHexSets);

            sb.append("### ").append(index).append(". ").append(name).append("\n\n");
            sb.append("- **Tag**: ")
                    .append(prov.tag() != null ? prov.tag() : "")
                    .append("\n");
            sb.append("- **Color**: ").append(prov.color()).append("\n");
            sb.append("- **Hex Count**: ").append(prov.hexes().size()).append("\n");
            sb.append("- **Center**: (")
                    .append(center[0])
                    .append(",")
                    .append(center[1])
                    .append(")\n");
            sb.append("- **Description**: ")
                    .append(prov.description() != null ? prov.description() : "")
                    .append("\n");
            if (!prov.annexedBy().isBlank()) {
                sb.append("- **Annexed By**: ⚔ ").append(prov.annexedBy()).append(" (不在地图上显示)\n");
            }
            sb.append("- **Address**: gsimap:region:").append(name).append("\n");

            if (detail) {
                sb.append("\n  **Terrain Composition:**\n");
                if (terrainComp.isEmpty()) {
                    sb.append("  (no data)\n");
                } else {
                    for (var te : terrainComp.entrySet()) {
                        sb.append("  - ")
                                .append(te.getKey())
                                .append(": ")
                                .append(te.getValue())
                                .append("\n");
                    }
                }

                sb.append("\n  **Adjacent Regions:**\n");
                if (adj.isEmpty()) {
                    sb.append("  (none)\n");
                } else {
                    for (var a : adj) {
                        sb.append("  - ")
                                .append(a.get("name"))
                                .append(" (shared edges: ")
                                .append(a.get("sharedEdges"))
                                .append(")\n");
                    }
                }
            } else {
                sb.append("(use detail=true for terrain composition and adjacency per region)\n");
            }
            sb.append("\n");
            index++;
        }

        return ToolResult.ok(
                name(),
                List.of(new ToolResult.Item(
                        "Regions: " + map.provinces().size() + " total", worldId + ":" + nodeId, sb.toString(), 1.0)));
    }

    @Override
    public Map<String, Object> getParameters() {
        Map<String, Object> props = new LinkedHashMap<>();
        props.put("worldId", Map.of("type", "string", "description", "GSim world ID"));
        props.put("nodeId", Map.of("type", "string", "description", "Node ID (optional, defaults to active node)"));
        props.put(
                "detail",
                Map.of(
                        "type",
                        "boolean",
                        "description",
                        "Set to true for full hex list (default: summary with stats only)"));
        return Map.of("type", "object", "properties", props, "required", List.of("worldId"));
    }

    @Override
    public Permission permission() {
        return Permission.READ;
    }
}
