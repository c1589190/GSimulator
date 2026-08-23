---
category: logic
date: 2026-08-01
severity: blocking
status: fixed
---

# NodeLoader.loadAttachmentFile 在 node 文件缺失时提前返回 null，导致 gsimap_generate 数据读不回

## 触发场景

1. 对**尚未创建 GSim 节点**（`nodes/n0000.json` 不存在）的全新 world 调用 `gsimap_generate`
2. `saveAttachmentFile` 会写入独立附件文件 `nodes/n0000_map.json`（node JSON 缺失时跳过 node 引用更新，但附件文件已落盘）
3. 随后任何查询工具（`gsimap_get_hex`、`gsimap_edge_set`、`gsimap_list_regions` 等）调用 `loadAttachmentFile`
4. `loadAttachmentFile` 第一行 `if (!Files.exists(nodeFile)) return null;` 直接返回 null
5. 所有查询报 `No map data for world: <worldId>`

复现（集成测试 Phase 4 实测）：
```
gsimap_generate {"worldId":"X","nodeId":"n0000",...} → success (hexCount=217)
gsimap_get_hex   {"worldId":"X",...}                → FAIL "No map data for world: X"
```

## 根因

`NodeLoader.java` 中 `saveAttachmentFile` 与 `loadAttachmentFile` 对"node 文件缺失"的容忍度不对称：

- **save**（写）：node 文件不存在 → 仍写附件文件，仅跳过 node JSON 引用更新（容错）
- **load**（读）：node 文件不存在 → **直接 return null**（不查附件文件）

命名约定 fallback（`nXXXX_<key>.json`）虽然存在，但被第 177 行的 early return 挡住，永远执行不到。

## 修复过程

文件：`gsim-lib/src/main/java/com/gsim/worldinfo/loader/NodeLoader.java`

将 legacy fallback 抽取为私有方法 `loadStandaloneAttachment(...)`，并在 node 文件缺失时先尝试读取独立附件文件，再决定返回 null：

```java
if (!Files.exists(nodeFile)) {
    // Map may be generated for a world with no GSim node yet — read the standalone attachment file.
    return loadStandaloneAttachment(worldsDir, worldId, nodeId, key, type);
}
```

`raw == null`（node JSON 无附件引用）分支同样复用该方法，行为不变。

**为什么这样修**：读写两侧语义对齐 —— 只要附件文件存在（无论 node JSON 是否存在）就能读到数据。不改 `saveAttachmentFile`（写侧容错保留），只补读侧 fallback。

## 检验方法

```bash
# 1. 重启 GSim 后，对全新 world（无 GSim node）直接 generate + 查询
curl -X POST http://127.0.0.1:8720/mcp -H "Content-Type: application/json" \
  -d '{"jsonrpc":"2.0","id":1,"method":"tools/call","params":{"name":"gsimap_generate","arguments":{"worldId":"vfy_x","nodeId":"n0000","seed":1,"radius":5}}}'
curl -X POST http://127.0.0.1:8720/mcp -H "Content-Type: application/json" \
  -d '{"jsonrpc":"2.0","id":1,"method":"tools/call","params":{"name":"gsimap_get_hex","arguments":{"worldId":"vfy_x","nodeId":"n0000","q":0,"r":0}}}'
# 期望: 第二个调用返回 success:true 且含地形信息（修复前报 "No map data for world"）

# 2. 回归：已有 node 的 world（附件引用路径 + 内联数据路径）不受影响
mvn test -pl gsim-lib -Dtest=AttachmentToolTest
```

## 关联文件

- `gsim-lib/src/main/java/com/gsim/worldinfo/loader/NodeLoader.java` — `loadAttachmentFile` / 新增 `loadStandaloneAttachment`
- 暴露路径：`gsimap` 模块所有地图工具经 `MapStore.loadFull` → `NodeLoader.loadAttachmentFile`
