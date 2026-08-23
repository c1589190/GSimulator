---
category: null
date: 2026-08-01
severity: high
status: fixed
---

# MapService.queryTerrain 以 null nodeId 调 resolve，导致必然 NPE

## 触发场景

`gsimap_query_terrain` 或任何调用 `MapService.queryTerrain(worldId, q, r)` 的路径，当世界**没有保存 contour** 时：

```java
if (contour == null) {
    MapData map = resolve(worldId, null);          // nodeId = null!
    MapData.HexCell cell = map.hexes().get(...);   // map 恒为 null → NPE
}
```

`resolve(worldId, null)` 链路：
1. `cacheKey` → `"worldId/null"`
2. `isRootNode(worldId, null)` → `NodeLoader.nodeFile` → 路径 `null.json`（`nodeId + ".json"`）→ 不存在 → 视为 root
3. `MapStore.loadFull(worldId, null)` → `loadAttachmentFile` → `null_map.json` 不存在 → 返回 null
4. `map.hexes()` → **NullPointerException**

## 根因

`MapService.java` 的 `queryTerrain` 传了字面量 `null` 作为 nodeId，且对 `resolve` 的 null 返回无防御。

## 修复过程

文件：`gsimap/src/main/java/com/gsimap/service/MapService.java`（queryTerrain）

改用 `resolveActive(worldId)`（读取活跃节点），并补 null 防御返回默认 water 采样；顺带统一用 `MapData.hexKey(q, r)`：

```java
MapData map = resolveActive(worldId);
if (map == null) return new ContourQueryEngine.TerrainSample(0, "water", "#3295D2");
MapData.HexCell cell = map.hexes().get(MapData.hexKey(q, r));
```

**为什么这样修**：无 contour 时按"活跃节点"语义读取（与 `queryTerrainBlock` 一致），而不是查询一个必然不存在的 `null.json` 节点。

## 检验方法

```bash
# 对无 contour 的世界调用 queryTerrain 相关工具
curl -X POST http://127.0.0.1:8720/mcp -H "Content-Type: application/json" \
  -d '{"jsonrpc":"2.0","id":1,"method":"tools/call","params":{"name":"gsimap_query_terrain","arguments":{"worldId":"W","q":0,"r":0}}}'
# 期望: 返回地形采样或默认 water（修复前抛 NullPointerException / "execution failed: null"）
```

## 关联文件

- `gsimap/src/main/java/com/gsimap/service/MapService.java` — queryTerrain
- 根因辅助：`gsim-lib/.../loader/NodeLoader.java` nodeFile 对 null nodeId 生成 `null.json` 路径
