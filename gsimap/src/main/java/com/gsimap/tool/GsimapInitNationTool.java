package com.gsimap.tool;

import static java.util.Map.entry;

import com.gsim.tool.AgentTool.Permission;
import com.gsim.tool.ToolCall;
import com.gsim.tool.ToolResult;
import com.gsim.util.JsonUtils;
import com.gsimap.service.MapService;
import java.io.IOException;
import java.util.List;
import java.util.Map;

/**
 * gsimap_init_nation — One-shot nation initialization.
 * Flood-fill unowned hexes from a seed, create the MapData province,
 * sync to GSim map checkpoint, and optionally write faction/narrative/worldview
 * checkpoint entries and a capital city.
 */
public final class GsimapInitNationTool extends AbstractGsimapTool {

    public GsimapInitNationTool(MapService mapService) {
        super(mapService);
    }

    @Override
    public String name() {
        return "gsimap_init_nation";
    }

    @Override
    public String description() {
        return "One-shot nation initialization: flood-fill unowned hexes from a seed, "
                + "create the MapData province, sync to GSim map checkpoint, and optionally "
                + "write faction/narrative/worldview checkpoint entries and a capital city.";
    }

    @Override
    public ToolResult execute(ToolCall call) {
        String worldId = com.gsim.mcp.GsimRequestContext.worldId();
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
        String name = call.param("name");
        if (name == null || name.isBlank()) {
            return ToolResult.fail(name(), "name is required");
        }

        String seedQStr = call.param("seedQ");
        String seedRStr = call.param("seedR");
        if (seedQStr == null || seedRStr == null) {
            return ToolResult.fail(name(), "seedQ and seedR (integers) are required");
        }
        int seedQ, seedR;
        try {
            seedQ = Integer.parseInt(seedQStr);
            seedR = Integer.parseInt(seedRStr);
        } catch (NumberFormatException e) {
            return ToolResult.fail(name(), "seedQ and seedR must be valid integers");
        }

        int maxHexes = parseIntParam(call, "maxHexes", 1000);
        String tag = call.param("tag");
        String color = call.param("color");
        String faction = call.param("faction");
        String narrative = call.param("narrative");
        String worldview = call.param("worldview");
        String capital = call.param("capital");
        String ruler = call.param("ruler");
        String religion = call.param("religion");

        try {
            Map<String, Object> result = mapService.initNation(
                    worldId, nodeId, name, seedQ, seedR, maxHexes, tag, color, faction, narrative, worldview, capital,
                    ruler, religion, false);
            result.put("address", "gsimap:region:" + name);
            if (capital != null && !capital.isBlank()) {
                result.put("address_city", "gsimap:city:" + capital);
            }
            return ToolResult.ok(
                    name(), List.of(new ToolResult.Item(name, "gsimap_init_nation", JsonUtils.toJson(result), 1.0)));
        } catch (IOException e) {
            return ToolResult.fail(name(), "Failed to initialize nation: " + e.getMessage());
        }
    }

    private static int parseIntParam(ToolCall call, String key, int defaultValue) {
        String val = call.param(key);
        if (val == null || val.isBlank()) return defaultValue;
        try {
            return Integer.parseInt(val);
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    @Override
    public Map<String, Object> getParameters() {
        return Map.of(
                "type", "object",
                "properties",
                        Map.ofEntries(
                                entry("worldId", Map.of("type", "string", "description", "GSim world ID")),
                                entry(
                                        "nodeId",
                                        Map.of(
                                                "type",
                                                "string",
                                                "description",
                                                "Node ID (optional, defaults to active node)")),
                                entry("name", Map.of("type", "string", "description", "Nation/province name")),
                                entry(
                                        "seedQ",
                                        Map.of(
                                                "type",
                                                "integer",
                                                "description",
                                                "Seed hex q (flood-fill start point)")),
                                entry("seedR", Map.of("type", "integer", "description", "Seed hex r")),
                                entry(
                                        "maxHexes",
                                        Map.of(
                                                "type",
                                                "integer",
                                                "description",
                                                "Max hexes to collect (default 1000)")),
                                entry("tag", Map.of("type", "string", "description", "Region tag (default: 'Nation')")),
                                entry(
                                        "color",
                                        Map.of(
                                                "type",
                                                "string",
                                                "description",
                                                "Region color hex (default: auto-generated)")),
                                entry(
                                        "faction",
                                        Map.of(
                                                "type",
                                                "string",
                                                "description",
                                                "Faction description text -> factions checkpoint")),
                                entry(
                                        "narrative",
                                        Map.of(
                                                "type",
                                                "string",
                                                "description",
                                                "Opening narrative text -> narrative checkpoint")),
                                entry(
                                        "worldview",
                                        Map.of(
                                                "type",
                                                "string",
                                                "description",
                                                "Worldview text -> worldview checkpoint (optional)")),
                                entry(
                                        "capital",
                                        Map.of(
                                                "type",
                                                "string",
                                                "description",
                                                "Capital city name -> creates city in map checkpoint")),
                                entry(
                                        "ruler",
                                        Map.of(
                                                "type",
                                                "string",
                                                "description",
                                                "Ruler name (optional, appended to faction tags)")),
                                entry(
                                        "religion",
                                        Map.of(
                                                "type",
                                                "string",
                                                "description",
                                                "Religion (optional, appended to faction tags)"))),
                "required", List.of("worldId", "name", "seedQ", "seedR"));
    }

    @Override
    public Permission permission() {
        return Permission.WRITE;
    }
}
