# Canvas 缓存键：evict() 用 worldId 匹配不到 worldId:nodeId

## 元信息

- **日期**: 2026-07-22
- **来源**: GPT 静态代码审查 (SpotBugs 未覆盖)
- **严重度**: 🔴 P1 错误结果
- **分支**: feat/gsimap-module
- **Commit**: 41369cf

## 触发场景

1. 对某节点生成或修改地图（生成 TerrainCanvas）
2. 修改地形后调用 `saveMap()` → 内部调 `evict()`
3. 再次查询同一节点的地图数据
4. **实际**: 返回旧 Canvas 中的地形数据（缓存未失效）
5. **期望**: 返回新地形数据

## 涉及文件

| 文件 | 行号 | 问题 |
|------|------|------|
| `gsimap/src/main/java/com/gsimap/service/MapService.java` | L949 | `canvases.remove(worldId)` 匹配不到 `worldId:nodeId` 格式的 key |
| `gsimap/src/main/java/com/gsimap/service/MapService.java` | L280-283 | `persistBlocks()` 在 `saveMap()` 之后重复调用 `evict()` |

## 修复前行为

```java
// canvasKey() 生成 "worldId:nodeId" 格式 (line 46-48)
private static String canvasKey(String worldId, String nodeId) {
    return worldId + ":" + nodeId;
}

// evict() 用纯 worldId 去 remove — 永远匹配不到 (line 949)
canvases.remove(worldId);
```

## 修复后行为

```java
// 用前缀匹配删除该 world 下所有 canvas
String canvasPrefix = worldId + ":";
canvases.keySet().removeIf(k -> k.startsWith(canvasPrefix));
```

## 修复代码

```diff
- canvases.remove(worldId);
+ // Evict all canvases for this world (keys are worldId:nodeId)
+ String canvasPrefix = worldId + ":";
+ canvases.keySet().removeIf(k -> k.startsWith(canvasPrefix));
```

同时移除了 `persistBlocks()` 中的重复 `evict()` 调用（`saveMap()` 内部已调过一次）。

## 为什么这样修

`canvases` 的 key 格式是 `worldId:nodeId`，而旧代码用纯 `worldId` 去 `remove`，
是典型的 key 格式不一致 bug。用 `removeIf` + 前缀匹配既修复了 bug，
又保留了"一个世界所有节点的 canvas 一起失效"的语义。

备选方案：改 `canvasKey()` 格式为只用 `worldId`。但这会导致不同节点共享同一个 canvas
实例，违反"每节点独立 canvas"的设计意图。

## 检验方法

```bash
# 编译通过
mvn compile -pl gsimap

# 全量验证（含 SpotBugs 零容忍）
mvn verify

# MCP 验证
# 1. gsimap_generate worldId="default"
# 2. 查询某 hex 地形
# 3. gsimap_update_terrain_type 修改地形
# 4. 再次查询同一 hex → 必须返回新地形
```

## 预防措施

- 已在 gsimap 启用 SpotBugs `failOnError=true`，类似问题会被静态分析捕获
- 缓存层应统一 key 生成与失效逻辑：考虑引入 `CacheKey` 类型而非裸 String 拼接
- 已在 CI 中启用 `mvn verify`，每次 push 都会验证
