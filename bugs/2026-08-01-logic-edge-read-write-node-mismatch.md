---
category: logic
date: 2026-08-01
severity: high
status: fixed
---

# setEdgeTag/removeEdgeTag 读取 active 节点却写入指定节点，导致跨节点数据污染

## 触发场景

`gsimap_edge_set` / `gsimap_edge_remove` 调用 `MapService.setEdgeTag(worldId, nodeId, ...)`：

1. `GsimapEdgeSetTool` 默认 `nodeId = "n0000"`（未传时）
2. `setEdgeTag` 内部 `resolveActive(worldId)` **读取叶子节点**（含整条 diff 链累积的全部状态）
3. `saveMap(worldId, nodeId, updated)` **保存到传入节点**（默认 n0000 根节点）

只要世界有子节点（active ≠ n0000），一次 edge 操作就会把叶子节点的完整解析状态**摊平写入根节点文件**，污染根节点数据；且活跃链上的修改被错误复制到根。

## 根因

`MapService.java` 中读操作 `resolveActive()` 与写操作 `saveMap(nodeId)` 的目标节点不一致 — 读写分离了两个不同的节点。

## 修复过程

文件：`gsimap/src/main/java/com/gsimap/service/MapService.java`（setEdgeTag / removeEdgeTag）

读写统一到同一节点：解析 `targetNode`（nodeId 为空时回退 active），读取与保存都用它：

```java
String targetNode = (nodeId == null || nodeId.isBlank()) ? readActiveNodeId(worldId) : nodeId;
MapData map = resolve(worldId, targetNode);
...
saveMap(worldId, targetNode, updated);
```

**为什么这样修**：工具显式传 nodeId 时语义就是"对该节点操作"，读取应与写入一致。回退 active 保持默认行为不变。

## 检验方法

```bash
# 创建 world → generate → node_create 子节点 → edge_set
# 修复前：根节点被叶子状态覆盖；修复后：仅目标节点数据变化
curl -X POST http://127.0.0.1:8720/mcp -H "Content-Type: application/json" \
  -d '{"jsonrpc":"2.0","id":1,"method":"tools/call","params":{"name":"gsimap_edge_set","arguments":{"worldId":"W","nodeId":"n0000","q1":0,"r1":0,"q2":1,"r2":0,"tag":"road"}}}'
# 期望: 只有 n0000 的地图数据含新边，子节点链不被摊平
```

集成测试 Phase 4b 验证：edge_set → render_text → edge_get 数据保留，多标签共存/删除互不误伤，全部 PASS。

## 关联文件

- `gsimap/src/main/java/com/gsimap/service/MapService.java` — setEdgeTag / removeEdgeTag
- `gsimap/src/main/java/com/gsimap/tool/GsimapEdgeSetTool.java` — nodeId 默认值入口
