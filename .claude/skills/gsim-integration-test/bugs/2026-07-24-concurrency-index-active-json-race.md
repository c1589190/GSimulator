# `_index.json` 并发读写竞争 + `active.json` 冗余 nodeId 导致 MCP 线程卡死

## 元信息

- **日期**: 2026-07-24
- **来源**: MCP 实战（集成测试并行 Agent）
- **严重度**: 🔴 P1 错误结果 + 线程卡死
- **分支**: feat/tool-mcp-refactor
- **Commit**: 335d09e (修复前), 854bdd2 (修复后)

## 触发场景

1. 多个 MCP Agent 并行调用 `gsim_world_create`（5 并发即可触发）
2. 每个 `world_create` 读 `_index.json` → 追加条目 → 写回
3. 线程 A 正在写 `_index.json`，线程 B 同时读 → 读到半截 JSON
4. Jackson 解析失败：`JSON deserialization failed: No content to map due to end-of-input`
5. 在 WSL2 的 9P 文件系统上，并发写同一文件可能导致虚拟线程 park 后无法唤醒（线程卡死）

类似问题也存在于 `node_create`：并发写 `active.json` 的 `nodeId` 字段。

## 涉及文件

| 文件 | 行号 | 问题 |
|------|------|------|
| `WorldIndexManager.java` | L49-60, L155-166, L184-221 | `_index.json` 的 load-then-modify-then-write 不是原子操作 |
| `ActiveStateManager.java` | L30-34 | `ActiveState` record 冗余的 `nodeId` 字段导致每次节点操作都写盘 |
| `NodeCreateTool.java` | L108-111 | 创建节点后写 `active.json`，与其他操作竞争 |
| `WorldDeleteTool.java` | L65-67 | 删除 world 后写 `_index.json` |

## 修复前行为

5 并发 `gsim_world_create`：1 个失败，报 `JSON deserialization failed: No content to map due to end-of-input`。
更严重时：3 个 `world_create` 全部卡死，MCP 日志只看到 `[MCP-HTTP-TOOL]` 没有 `TOOL-RESULT`，虚拟线程 park 在文件 I/O 上无法恢复。

## 修复后行为

10 并发 `gsim_world_create`：全部成功。每个 world 只写自己的 `worlds/{id}/` 子目录，零共享竞争。

## 修复代码

```diff
WorldIndexManager.java:
- // _index.json — lock to prevent concurrent read/write races
- synchronized (WORLD_CREATE_LOCK) {
-     entries = new ArrayList<>(loadIndexEntries(worldsDir));
-     entries.add(new WorldEntry(worldId, name, now));
-     saveIndex(worldsDir, entries);
- }
+ // No global index file — listWorlds() scans directories instead

ActiveStateManager.java:
- public record ActiveState(String nodeId, Map<String, String> sessions)
+ public record ActiveState(Map<String, String> sessions)

NodeCreateTool.java:
- ActiveStateManager.ActiveState currentState = ActiveStateManager.load(worldsDir, worldId);
- Map<String, String> sessions = currentState != null ? currentState.sessions() : new LinkedHashMap<>();
- ActiveStateManager.save(worldsDir, worldId, new ActiveStateManager.ActiveState(newNodeId, sessions));
+ // nodeId is now tracked in-memory by SessionPool — no disk write needed
```

## 为什么这样修

不是加锁（治标），而是**消灭共享可变文件**（治本）。`_index.json` 本质上是个冗余索引 —— 目录扫描就能获取同样的信息，不需要维护额外的索引文件。`active.json` 的 `nodeId` 字段是 CLI 时代的遗留，MCP 层早已要求显式传 `nodeId`，持久化到磁盘没必要。

## 检验方法

```bash
# 编译 + 测试
mvn clean verify

# 10 并发 world_create
for i in $(seq 1 10); do
  curl -s --max-time 10 -X POST http://127.0.0.1:8720/mcp \
    -H "Content-Type: application/json" \
    -d "{\"method\":\"tools/call\",\"params\":{\"name\":\"gsim_world_create\",\"arguments\":{\"worldId\":\"mcp_conc_${i}\",\"name\":\"并发${i}\"}},\"jsonrpc\":\"2.0\",\"id\":${i}}" &
done
wait
# 期望: 10/10 PASS，0 错误，0 超时
```

## 预防措施

- **原则**：任何被多个 MCP 工具共享读写的全局文件都是潜在竞争点。新增持久化状态时优先考虑：
  1. 能否完全内存化（如 nodeId 由 SessionPool 跟踪）？
  2. 能否按 world 隔离（每个 world 只写自己的子目录）？
  3. 只有真正需要跨 world 查询且无法从目录扫描得出的数据才考虑全局索引文件
- `listWorlds()` 现在是纯目录扫描，world 数量 < 100 时性能无影响。如果未来 world 数量增长到 1000+，可考虑用内存缓存而非磁盘索引
