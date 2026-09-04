package com.gsim.map.tools.map;

import com.gsim.agentsmanager.tool.ToolCall;
import com.gsim.agentsmanager.tool.ToolResult;
import com.gsim.map.map.MapData;
import com.gsim.map.service.MapService;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * gsimap_remove_hex_tag — Remove a single tag from a hex cell.
 */
public final class GsimapRemoveHexTagTool extends AbstractGsimapTool {

    public GsimapRemoveHexTagTool(MapService mapService) {
        super(mapService);
    }

    @Override
    public String name() {
        return "gsimap_remove_hex_tag";
    }

    @Override
    public String description() {
        return "Remove a single tag from a hex cell. "
                + "Parameters: worldId (required), nodeId (required, target node to write), "
                + "q/r (required, hex axial coordinates), tagKey (required).";
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
        props.put("tagKey", Map.of("type", "string", "description", "Tag key to remove"));
        return Map.of(
                "type", "object", "properties", props, "required", List.of("worldId", "nodeId", "q", "r", "tagKey"));
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
        String tagKey = call.param("tagKey");

        try {
            MapData after = mapService.removeHexTag(worldId, nodeId, q, r, tagKey);

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
                                            "removed",
                                            tagKey,
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

    @Override
    public Permission permission() {
        return Permission.WRITE;
    }
}
