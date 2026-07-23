# GSimulator 系统架构

GSimulator 是一个基于 Java 21 + Maven 多模块（`gsim-lib`, `gsimap`, `gsim-app`）的多 Agent 推演工作流引擎，服务于中文架空历史推演场景。支持 CLI REPL、HTTP API + SSE 流式输出、Web UI、Canvas 六角格地图编辑器、MCP (Model Context Protocol) stdio 服务器。

面向 AI Agent 的开发指南见项目根目录的 CLAUDE.md。

---

## 1. 模块依赖关系

```
gsim-app (CLI 入口 / MCP --no-cli)
   ├── gsimap (六角格地图模块)
   │       └── gsim-lib (核心引擎)
   └── gsim-lib
```

- **gsim-lib** — 核心引擎。Agent 系统、工具注册、WorldInfo 存储、LLM 管理、事件总线、HTTP/WebSocket 服务、文档管理、资料导入管道。
- **gsimap** — 六角格地图模块。MapService、MapStore（JSON 持久化 + diff-chain 历史）、MapGenerator（程序化地形）、25 个 MCP 工具、独立 HTTP 服务器（:8711）。
- **gsim-app** — 启动入口。`com.gsim.Main` 三阶段启动（配置加载 → 应用组装 → 传输启动）。

---

## 2. 分层架构

```
┌───────────────────────────────────────────────────┐
│                 Presentation Layer                 │
│   CLI REPL (JLine) / HTTP API (:8710) / MCP stdio  │
│   Map Editor UI (:8711) / CLI WebSocket (:8712)    │
├───────────────────────────────────────────────────┤
│               Application Assembly                 │
│   GSimulatorApplication — manual DI                │
│   ApplicationContext (no Spring, no DI framework)  │
├───────────────────────────────────────────────────┤
│                  Agent System                      │
│   ToolLoop: AbstractAgent → OrchestratorAgent      │
│   SubAgent dispatch on virtual threads             │
│   ToolRegistry (95+ tools, 5 groups)              │
├───────────────────────────────────────────────────┤
│              Data & Storage Layer                  │
│   WorldInfo (Node → Checkpoint → Element)         │
│   DocStore (docs/ dir), CacheStore (caches/ dir)   │
│   Gsimap: MapStore (JSON files, diff-chain)       │
├───────────────────────────────────────────────────┤
│                 Infrastructure                     │
│   LLM Manager (multi-provider, streaming)          │
│   EventBus (CLI/SSE/WebSocket sinks)               │
│   Import pipeline (local files, MediaWiki, web)    │
└───────────────────────────────────────────────────┘
```

---

## 3. 三阶段启动流程

```
Phase 1: ConfigLoader
  CLI args → gsim.properties + env vars → AppConfig
  Bootstrap: worlds/ → WorldInformation → Cache
  └── 交互终端：缓存选择（历史/新建）

Phase 2: Application Assembly
  GSimulatorApplication → ApplicationContext
    ├── ToolRegistry (70+ gsim-lib tools)
    ├── GsimapToolRegistrar.registerAll() (+25 gsimap tools)
    ├── MapService + MapStore
    ├── AgentFactory (SubAgent 虚拟线程生命周期)
    └── 3 个 HTTP 服务器

Phase 3: Transport
  ┌── 默认:  CLI REPL（阻塞，/chat /world /node 命令）
  └── --no-cli: MCP stdio server（阻塞，JSON-RPC 2.0）
  两者都启动：
    ├── WebUiServer       (:8710)
    ├── GsimapHttpServer  (:8711)
    └── CliWebSocketServer(:8712)
```

---

## 4. 三个服务器

| 服务器 | 端口 | 实现 | 用途 |
|--------|------|------|------|
| WebUiServer | 8710 | `com.sun.net.httpserver.HttpServer` + Thymeleaf + HTMX + Tailwind CSS | 主 Web UI + REST API + SSE 事件流 |
| GsimapHttpServer | 8711 | `com.sun.net.httpserver.HttpServer` + Canvas 原生 JS | 六角格地图编辑器 |
| CliWebSocketServer | 8712 | 原生 Socket + WebSocket 握手（无框架） | 实时聊天流式传输（CLI 文本协议 + Chat JSON 协议） |

---

## 5. Agent 系统架构

核心模式：**ToolLoop** — LLM 在循环中调用工具，直到显式调用 `finish_action`。

```
用户输入 → AbstractAgent.executeToolLoop()
  ├── System prompt（AgentConfig JSON 内嵌）
  ├── 工具列表（按 ToolGroup + ToolFilter 过滤）
  └── while (round <= maxRounds):
        ├── LLM 调用（流式/非流式）
        ├── 解析 tool_calls（API 原生优先 → 文本 JSON fallback）
        ├── PermissionGate 门禁检查
        ├── 工具执行 → 结果反馈
        └── finish_action → 验证 → 最终文本
```

### 核心类

| 类 | 职责 |
|----|------|
| `AbstractAgent` | ToolLoop 基类（~500 行），子类通过钩子扩展 |
| `OrchestratorAgent` | 主协调者（~1460 行），覆盖权限门禁、工具组管理、流式处理、finish_action 验证 |
| `AgentFactory` | 从 AgentConfig JSON 创建 Agent 实例，在虚拟线程上管理 SubAgent 生命周期 |
| `AgentConfigStore` | 从 `data/agents/*.json` 加载配置（含 classpath fallback） |
| `AgentConfigManager` | 运行时 CRUD（list/get/update field），原子写入 + 自动 reload |

### Agent 类型

| Agent | 最大轮数 | 温度 | 工具权限 | 用途 |
|-------|---------|------|---------|------|
| orchestrator | 64 | 0.3 | all（全部工具） | 主控 Agent，可派发 SubAgent |
| sim | 16 | 0.5 | read_only | 推演叙事生成 |
| search | 16 | 0.3 | read_only | 多源资料搜索 |

### 三个扩展点

1. `beforeToolExecute` — 权限门禁（PermissionGate）
2. `afterToolExecute` — 缓存持久化
3. `effectiveMaxToolRounds` — 动态轮数上限

---

## 6. 工具系统

### 接口定义

```java
public interface AgentTool {
    String name();
    String description();
    ToolResult execute(ToolCall call);
    Map<String, Object> getParameters();  // JSON Schema, null = 宽 schema
    Permission permission();              // SELF < READ < WRITE < SYSTEM
    boolean requiresWorldId();            // MCP 自动注入 worldId
}
```

### 权限等级

| 等级 | 门禁规则 |
|------|---------|
| SELF | Agent 流程控制（finish_action 等），自动允许 |
| READ | 只读查询，自动允许 |
| WRITE | 写入/修改，CLI 首次需用户确认（Y/N/A） |
| SYSTEM | 破坏性操作（delete_*），永远需确认 |

### 工具调用分类（ToolCategory）

| 分类 | 门禁 |
|------|------|
| READ_ONLY | 自动允许 |
| MUTATING | CLI 模式首次需用户确认 |
| DESTRUCTIVE | 永远需确认，不允许"本轮全部允许" |
| CONTROL | 自动允许（finish_action, activate_tool_groups） |

### 5 个工具组（通过 `activate_tool_groups` 按需激活）

| 工具组 key | 成员工具 |
|-----------|---------|
| `world_info` | query_node, query_checkpoint, query_keyword, query_element, write_element, create_checkpoint |
| `node_mgmt` | node_list, node_status, node_create, node_switch, node_goto_parent |
| `import_doc` | import_document_list, import_document_read, import_document_search |
| `search` | wiki_search, mediawiki_search |
| `docs` | doc_list, doc_read, doc_create, doc_write, doc_search, doc_index, doc_crop, doc_template |

### 默认工具（始终可用，无需激活）

`finish_action`, `activate_tool_groups`, `dispatch_sub_agent`, `collect_sub_agent_results`, SubAgent 缓存管理工具（list/view/view_output）、World CRUD（world_list/create/switch）、`compact_cache`、以及 `doc_list/read/create/write/search/index`。

### finish_action 验证

拒绝以下输出模式：
- `[工具调用已执行]` 占位符
- `[工具结果]` / `[TOOL_RESULT]` 伪造标记
- Fenced JSON / 裸 JSON tool call
- `{key=value}` 伪造工具输出（MODEL_FAKE_TOOL_RESULT 检测）
- 验证失败 → 打回 LLM 重写，不消耗额外轮次配额

---

## 7. MCP Bridge

```
MCP Client (Claude Desktop, IDE)
        │ JSON-RPC 2.0 over stdio
        ▼
  AbstractMcpServer (协议基类)
        │ initialize / tools/list / tools/call
        ▼
  GsimMcpServer (具体实现)
        │
        ├── ToolRegistryMcpAdapter: AgentTool → MCP ToolDef 双向映射
        ├── worldId auto-injection（requiresWorldId()=true 的工具）
        └── 分页注入: _page / _pageSize 自动添加到所有工具 schema
```

- 核心工具使用 `gsim_` 前缀（如 `gsim_query_node`）
- 地图工具使用 `gsimap_` 前缀（如 `gsimap_get_hex`）
- 使用原始 stdout（MCP 模式中 System.out 被重定向到 stderr，防止协议污染）

---

## 8. GSimap 六角格地图

### 数据模型

`MapData` — 根 record，包含：
- `hexes`: `Map<String, HexCell>` — 所有六角格，key 为 `q_r` 坐标
- `provinces`, `cities`: 省/城市定义
- `terrainTypes`: 地形类型定义
- `terrainBlocks`: 有序多边形地形块
- `pathwayGroups`: 路径组定义（river, road 等），含属性 schema
- `edges`: 稀疏边映射 — `edgeKey → {pathwayId → {属性}}`，仅存储非默认值
- `compressedRegions`: 缓存的轮廓凸包（用于前端渲染加速）

### 核心服务

| 服务 | 职责 |
|------|------|
| `MapService` | LRU 缓存（32 条目），统一 API |
| `MapStore` | JSON 文件持久化，diff-chain 历史 |
| `MapGenerator` | 程序化地形生成（SimplexNoise + 大陆轮廓） |
| `MapResolver` | diff-chain 快照重建 |

### 25 个 MCP 工具

9 个查询 + 1 个地址解析 + 2 个 diff/历史 + 7 个区域 CRUD + 4 个边通路 + 2 个初始化工具，总计 25 个工具（详见 `TOOL-CONTRACTS.md`）。

---

## 9. WorldInfo 存储系统

四层模型：

```
World
  └── Node (时间线快照，n0000 根节点开始)
        └── Checkpoint (分类容器：worldview, characters, factions, player.*)
              └── Element (key-value 对，带 tags/links/type 元数据)
```

- **关键字索引** — 所有 Element 全文搜索，支持分页、checkpointId 过滤
- **通配符查询** — `query_checkpoint` 支持 `player.*` 通配
- **节点分支** — `node_create` 创建子节点并自动切换，`node_switch` / `node_goto_parent` 导航
- **持久化** — 每个节点一个 `nXXXX.json` 文件，存储在 `data/worlds/{worldId}/` 目录

---

## 10. 事件系统

```
AgentProgressSink 链
  └── CompositeAgentProgressSink
        ├── CliAgentProgressSink (JLine 终端渲染)
        ├── EventBusAgentProgressSink → EventBus → SseEventSink
        └── SessionPoolBridge → SessionPool → WebSocket 推送
```

### SSE 事件类型

`command_started`, `command_done`, `command_error`, `log`, `run_stage`, `import_progress`, `search_progress`, `tool_started`, `tool_done`, `tool_error`, `llm_started`, `llm_delta`, `llm_reasoning_delta`, `llm_done`, `result`, `done`

事件包含 sessionId、taskId、type、time、data 字段，通过 `FilteredEventSink` 按 session + task 过滤。

---

## 11. 跨参考文档

| 文档 | 内容 |
|------|------|
| `MODULES.md` | 模块职责与包结构详解 |
| `DATA-MODEL.md` | 核心数据结构定义（MapData, HexCell, WorldInfo, ToolCall 等） |
| `RUNTIME-FLOWS.md` | 关键运行时序列（工具调用、SubAgent 派发、地图编辑） |
| `TOOL-CONTRACTS.md` | 工具接口契约、分类、权限、参数 schema |
| `TESTING.md` | 测试策略与 FakeLlmManager 使用 |
| `DEVELOPMENT.md` | 开发环境搭建与贡献指南 |
| `CLAUDE.md` (项目根目录) | AI Agent 开发指南 — 架构原则、禁止事项、提交前检查清单 |
