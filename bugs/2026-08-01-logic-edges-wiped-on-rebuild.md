---
category: logic
date: 2026-08-01
severity: high
status: fixed
---

# MapService 多处重建 MapData 时 edges 传 Map.of()，静默清空 pathway 边标签

## 触发场景

先用 `gsimap_edge_set` 写入边标签（如 road/river），随后执行以下任一操作：

- `gsimap_expand`（地图扩展）
- `gsimap_compress` / `gsimap_decompress` / `gsimap_decompress_at`（区域压缩）
- `gsimap_rename_region`（区域重命名）
- `add_block` / `remove_block`（terrain block 持久化，走 `persistBlocks`）

操作后 `edge_get` 发现**全部 pathway 边标签消失** — 数据静默丢失，无任何报错。

## 根因

`MapService.java` 多处构造新 `MapData` 时，edges 参数硬编码为 `Map.of()`：

```java
map.pathwayGroups(),
Map.of());   // ← edges 被清空
```

rivers/roads 字段用 `List.of()` 是**有意废弃**（有注释说明，将由 PathwayGroup 系统替代），但 **edges 不是废弃字段** — `setEdgeTag`/`removeEdgeTag` 仍在写入，`withProvinces`/`withTerrainTypes` 也正确保留 `source.edges()`。仅这 6 处重建路径遗漏。

## 修复过程

文件：`gsimap/src/main/java/com/gsimap/service/MapService.java`

6 处 `Map.of()` → `map.edges()`：persistBlocks、renameRegion、expand、compress、decompress、decompressAt。

**为什么这样修**：重建 MapData 时应透传既有 edges（与 withProvinces/withTerrainTypes/setEdgeTag 的保留行为一致），只在真正生成新地图（generate）时才是空 edges。

## 检验方法

```bash
# 1. edge_set 设边标签 → 2. 执行 expand 或 compress → 3. edge_get 复查
curl -X POST http://127.0.0.1:8720/mcp -H "Content-Type: application/json" \
  -d '{"jsonrpc":"2.0","id":1,"method":"tools/call","params":{"name":"gsimap_edge_get","arguments":{"worldId":"W","nodeId":"n0000","q1":0,"r1":0,"q2":1,"r2":0}}}'
# 期望: 边标签仍在（修复前返回空 tags / 边消失）
```

集成测试 Phase 4b 步骤 9 实测通过：edge_set → render_text 等操作后边标签完整保留。

## 关联文件

- `gsimap/src/main/java/com/gsimap/service/MapService.java` — persistBlocks / renameRegion / expand / compress / decompress / decompressAt
