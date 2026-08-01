---
category: logic
date: 2026-08-01
severity: high
status: fixed
---

# WorldInformation.upsertElement 移除索引时未按 nodeId 过滤，跨节点误删历史

## 触发场景

分支链上有 n0000 和 n0001，**两个节点在同一 checkpoint 有相同 key 的元素**（如 `shared_key`）：

1. n0000 写入 `shared_key = V1`
2. n0001 `upsertElement("shared_key", V2)` → 触发 `removeRefFromIndexes`
3. `byCheckpoint` / `byTag` 索引中**所有** `key == "shared_key"` 的引用被移除 — 包括 n0000 的 V1 历史
4. `query_checkpoint` / `query_by_tag` / 关键词索引丢失 n0000 的历史条目

## 根因

`WorldInformation.java` 的 `removeRefFromIndexes`：

```java
cpList.removeIf(r -> r.element().key().equals(ref.element().key()));
// 缺少 r.nodeId().equals(ref.nodeId()) 条件
```

只按 key 匹配，没有限定同一节点。同 key 在不同节点是独立的历史条目。

## 修复过程

文件：`gsim-lib/src/main/java/com/gsim/worldinfo/WorldInformation.java`（removeRefFromIndexes）

byCheckpoint 和 byTag 的 removeIf 均增加 nodeId 相等条件：

```java
cpList.removeIf(r -> r.nodeId().equals(ref.nodeId()) && r.element().key().equals(ref.element().key()));
```

**为什么这样修**：upsert 只应替换"同一节点、同一检查点、同一 key"的旧引用；其他节点的同 key 历史属于独立语义，必须保留。keywordIndex 无移除 API 的已知限制保留（旧引用在会话内残留但重建后消失）。

## 检验方法

```bash
# 1. world 创建后: n0000 写 shared_key=V1
# 2. node_create 子节点 n0001
# 3. n0001 写 shared_key=V2 (upsert)
# 4. query_checkpoint 查 n0000 的 shared_key → 必须仍返回 V1
curl -X POST http://127.0.0.1:8720/mcp -H "Content-Type: application/json" \
  -d '{"jsonrpc":"2.0","id":1,"method":"tools/call","params":{"name":"gsim_query_element","arguments":{"worldId":"W","ref":"n0000:test_cp:shared_key"}}}'
# 期望: snippet 含 V1（修复前返回空/无条目）
```

集成测试 Phase 5 步骤 12d 实测通过：n0000 的 V1 保留，n0001 的 V2 独立存在。

## 关联文件

- `gsim-lib/src/main/java/com/gsim/worldinfo/WorldInformation.java` — removeRefFromIndexes / upsertElement
