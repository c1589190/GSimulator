# Board 指令 — 与 World-Node 绑定的公开展示板

## 设计

### 存储

```
docs/board/{worldId}-{nodeId}-{title}.md

例:
docs/board/default-n0002-曹操的动向.md
docs/board/default-n0002-局势分析.md
docs/board/three_kingdoms-n0005-赤壁战报.md
```

### 命名规则

`{worldId}-{nodeId}-{title}` — 由系统自动拼接，Agent 只需提供 title。
用户可以通过 CLI 修改 title（但 worldId-nodeId 前缀不可改）。

### CLI 指令

```
/board list              — 列出当前世界+当前节点的所有 board
/board read <id>         — 读取指定 board 全文
/board create <title>    — 在当前节点下创建新 board
/board write <id> <content> — 写入 board（替换全文）
/board append <id> <content> — 追加到 board
```

### Agent 侧

Agent 通过现有 doc 工具操作 board：
```
doc_create(docId="board:default-n0002-曹操的动向", type="board", title="曹操的动向")
doc_write(docId="board:default-n0002-曹操的动向", content="## 公开情报\n...")
doc_list(type="board")  — 过滤所有 board
```

写入 `type=board` 的文档时，自动作为公开消息推送给用户。

### 自动推送

当 Agent 写入 board 类型 doc 时，`DocWriteTool` 自动触发 `publicMessage` 事件，
CLI/Web UI 立即显示。

## 实现步骤

### Step 1: DocType 新增 BOARD 类型
- `DocType.java` 新增 `BOARD("board", "展示板")`

### Step 2: BoardCommand（CLI 指令）
- `BoardCommand.java` — `/board list|read|create|write|append`
- 注入 `DocStore`, `WorldInformation`(获取当前 world/node)
- `create`: 自动拼接 `{worldId}-{nodeId}-{title}` 作为 docId

### Step 3: 注册到 ConsoleInteractionAdapter
- `ConsoleInteractionAdapter` 新增 `/board` 路由

### Step 4: DocWriteTool 自动推送
- 写入 `type=board` 的 doc 时触发 `AgentProgressEvent.publicMessage(content)`

### Step 5: 更新 Orchestrator prompt
- 告知 Agent board 的存在和用法

## 涉及文件

| 文件 | 变更 |
|------|------|
| `DocType.java` | 新增 BOARD 枚举 |
| `BoardCommand.java` | 新增 CLI 指令 |
| `ConsoleInteractionAdapter.java` | 注册 /board 路由 |
| `DocWriteTool.java` | board 类型自动推送 |
| `GSimulatorApplication.java` | 创建 BoardCommand 并注入 |
| `orchestrator/config.json` | prompt 新增 board 说明 |
