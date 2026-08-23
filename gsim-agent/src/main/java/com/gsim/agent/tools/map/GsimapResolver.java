package com.gsim.agent.tools.map;

import com.gsim.core.ref.RefResolver.ResolvedRef;
import com.gsim.core.ref.Resolver;
import com.gsim.core.ref.ResolverContext;
import com.gsim.core.util.JsonUtils;
import com.gsim.map.map.MapData;
import com.gsim.map.service.MapService;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * {@code gsimap:} 前缀引用解析器 — 将地图地址解析为对应实体（省/区域、单格、城市、地形）。
 *
 * <p>支持的地址格式（与 {@link GsimapQueryByAddressTool} 一致）：
 * <ul>
 *   <li>{@code gsimap:region:{name}} — 按名称查找省/区域</li>
 *   <li>{@code gsimap:hex:{q}_{r}} — 查找单个六角格</li>
 *   <li>{@code gsimap:hex:{q}_{r}:tag:{tag_key}} — 按标签键解析 hex 的单个标签</li>
 *   <li>{@code gsimap:city:{name}} — 按名称查找城市</li>
 *   <li>{@code gsimap:terrain:{key}} — 查找地形类型定义</li>
 * </ul>
 *
 * <p>依赖 {@link MapService}，因此位于 gsim-agent 层（gsim-core 不依赖 gsim-map）；
 * 由应用装配时注册进 {@link com.gsim.core.ref.ResolverRegistry}。
 */
public final class GsimapResolver implements Resolver {

    private final MapService mapService;

    public GsimapResolver(MapService mapService) {
        this.mapService = mapService;
    }

    @Override
    public String prefix() {
        return "gsimap";
    }

    @Override
    public ResolvedRef resolve(String path, ResolverContext ctx) {
        if (path.isBlank()) throw new IllegalArgumentException("gsimap: path must not be blank");
        if (ctx.activeWorldId() == null || ctx.activeWorldId().isBlank()) {
            throw new IllegalStateException("No active world set");
        }

        String worldId = ctx.activeWorldId();
        String nodeId = mapService.readActiveNodeId(worldId);
        MapData map = mapService.resolve(worldId, nodeId);
        if (map == null) {
            throw new IllegalArgumentException("No map data for world=" + worldId + " node=" + nodeId);
        }

        // 解析 gsimap:<entityType>:<entityId>
        int colonIdx = path.indexOf(':');
        if (colonIdx < 0) {
            throw new IllegalArgumentException("Invalid gsimap address format: gsimap:" + path);
        }
        String entityType = path.substring(0, colonIdx);
        String entityId = path.substring(colonIdx + 1);
        if (entityId.isBlank()) {
            throw new IllegalArgumentException("gsimap:" + entityType + " id must not be blank");
        }

        return switch (entityType) {
            case "region" -> resolveRegion(map, entityId);
            case "hex" -> resolveHex(map, entityId);
            case "city" -> resolveCity(map, entityId);
            case "terrain" -> resolveTerrain(map, entityId);
            default -> throw new IllegalArgumentException(
                    "Unknown gsimap entity type: " + entityType + ". Valid types: region, hex, city, terrain");
        };
    }

    private ResolvedRef resolveRegion(MapData map, String name) {
        MapData.Province province = map.provinces().get(name);
        if (province == null) {
            throw new IllegalArgumentException("Region not found: " + name);
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("address", "gsimap:region:" + name);
        result.put("entityType", "region");
        result.put("name", name);
        result.put("tag", province.tag());
        result.put("color", province.color());
        result.put("hexCount", province.hexes().size());
        result.put("hexes", province.hexes());
        return new ResolvedRef("gsimap", "gsimap:region:" + name, name, JsonUtils.toJson(result));
    }

    private ResolvedRef resolveHex(MapData map, String hexKey) {
        // entityId 可能是 "q_r"（整格）或 "q_r:tag:<tagKey>"（单标签）——按第一个冒号切分
        int colon = hexKey.indexOf(':');
        String cellKey = hexKey;
        String rest = null;
        if (colon >= 0) {
            cellKey = hexKey.substring(0, colon);
            rest = hexKey.substring(colon + 1);
        }
        MapData.HexCell cell = map.hexes().get(cellKey);
        if (cell == null) {
            throw new IllegalArgumentException("Hex not found: " + cellKey);
        }

        String tagKey = null;
        String tagValue = null;
        String address = "gsimap:hex:" + cellKey;
        if (rest != null) {
            if (!rest.startsWith("tag:") || rest.substring(4).isBlank()) {
                throw new IllegalArgumentException("Invalid hex tag address: gsimap:hex:" + hexKey);
            }
            tagKey = rest.substring(4);
            tagValue = cell.tags().get(tagKey);
            if (tagValue == null) {
                throw new IllegalArgumentException("Tag not found: " + tagKey + " on hex " + cellKey);
            }
            address = "gsimap:hex:" + cellKey + ":tag:" + tagKey;
        }

        String owner = null;
        for (var entry : map.provinces().entrySet()) {
            if (entry.getValue().hexes().contains(cellKey)) {
                owner = entry.getKey();
                break;
            }
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("address", address);
        result.put("entityType", "hex");
        result.put("hexKey", cellKey);
        result.put("terrain", cell.terrain());
        result.put("color", cell.color());
        result.put("symbol", cell.symbol());
        if (owner != null) result.put("province", owner);
        if (tagKey != null) {
            result.put("tagKey", tagKey);
            result.put("tagValue", tagValue);
        }
        if (tagKey == null) {
            // 无 tag 子段：现有整格语义完全不变（id/content 保持原形）
            return new ResolvedRef("gsimap", "gsimap:hex:" + cellKey, cellKey, JsonUtils.toJson(result));
        }
        return new ResolvedRef("gsimap", address, address, JsonUtils.toJson(result));
    }

    private ResolvedRef resolveCity(MapData map, String name) {
        MapData.City city = map.cities().get(name);
        if (city == null) {
            throw new IllegalArgumentException("City not found: " + name);
        }
        String hexKey = MapData.hexKey(city.q(), city.r());
        String owner = null;
        for (var entry : map.provinces().entrySet()) {
            if (entry.getValue().hexes().contains(hexKey)) {
                owner = entry.getKey();
                break;
            }
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("address", "gsimap:city:" + name);
        result.put("entityType", "city");
        result.put("name", name);
        result.put("q", city.q());
        result.put("r", city.r());
        if (owner != null) result.put("province", owner);
        result.put("description", city.description());
        return new ResolvedRef("gsimap", "gsimap:city:" + name, name, JsonUtils.toJson(result));
    }

    private ResolvedRef resolveTerrain(MapData map, String key) {
        MapData.TerrainType terrain = map.terrainTypes().get(key);
        if (terrain == null) {
            throw new IllegalArgumentException("Terrain type not found: " + key);
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("address", "gsimap:terrain:" + key);
        result.put("entityType", "terrain");
        result.put("key", key);
        result.put("name", terrain.name());
        result.put("color", terrain.color());
        result.put("food", terrain.food());
        result.put("gold", terrain.gold());
        result.put("stone", terrain.stone());
        result.put("moveCost", terrain.moveCost());
        return new ResolvedRef("gsimap", "gsimap:terrain:" + key, key, JsonUtils.toJson(result));
    }
}
