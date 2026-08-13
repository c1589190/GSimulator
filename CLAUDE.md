# GSimulator — Claude 开发指南

## 项目简介

GSimulator 是一个基于 Java 21 + Maven 的多 Agent 推演工作流引擎，服务于"文游 / 架空历史 / 玩家行动推演"场景。它不是普通聊天机器人，而是一个可审计、可回放、可扩展的回合制推演系统。

支持 CLI REPL（JLine）、HTTP API + SSE 流式事件输出、Web UI（WebUiServer，JDK 内嵌 HttpServer，端口 8710）。

## 架构原则

1. **交互层抽象** — 所有输入输出通过 `InteractionManager` / `ConsoleInteractionAdapter`，不允许业务代码直接读写控制台
2. **LLM 统一封装** — 所有 LLM 调用通过 `LlmManager` / `LlmProvider` 接口，支持多 provider 切换
3. **Prompt 外置** — Prompt 存储在 `resources/gsim/prompts/`（Markdown 文件）和 `resources/gsim/agents/*/config.json`（Agent 配置内嵌），不在 Java 代码中写死复杂 prompt
4. **配置集中** — 所有环境变量读取统一走 `AppConfig`（`gsim.properties` 文件 + 环境变量覆盖）
5. **离线测试** — 所有测试使用 `FakeLlmManager`，不依赖外部服务
6. **DTO 优先 record** — 不可变数据优先使用 Java record
7. **工具注册制** — 所有 Agent 可调用的能力通过 `AgentTool` 接口 + `ToolRegistry` 注册，不硬编码

## Package 说明

```
com.gsim
├── app/               — 应用启动（GSimulatorApplication）、依赖注入
├── agent/             — Agent 核心系统
│   ├── core/          — AbstractAgent（统一 ToolLoop）、AgentConfig、AgentFactory、
│   │                     AgentResult、AgentRound、ToolFilterConfig
│   ├── config/        — AgentConfigStore（JSON 加载）、AgentConfigManager（运行时 CRUD）
│   └── tool/          — Agent 管理工具（dispatch_sub_agent、collect_sub_agent_results、
│                         activate_tool_groups、view_sub_agent_cache 等 11 个工具）
├── cache/             — SubAgent 对话缓存（CacheSession、CacheStore、CachesManager）
├── commands/          — CLI 命令实现（AgentCommand、ChatCommand、CompactCommand、
│                         LlmCommand、NodeCommand、WorldCommand）
├── compact/           — 缓存压缩（摘要生成）
├── config/            — AppConfig（gsim.properties + 环境变量）
├── event/             — 统一事件系统（EventBus、GSimEvent、ConsoleEventSink、SseEventSink）
├── importing/         — 导入工具实现（import_document_list/read/search）
│   └── tool/          — 导入工具 AgentTool 封装
├── interaction/       — 交互层（CLI REPL、CommandParser、ConsoleInteractionAdapter）
├── llm/               — LLM 客户端统一封装
│   │                     LlmManager（统一入口）、LlmProviderRegistry（多 provider）、
│   │                     LlmCall（异步提交）、StreamPool（流式缓冲）、
│   │                     LlmConfigManager（provider 配置管理）
├── session/           — Session 管理（SessionPool、SessionNode、SessionPoolBridge）
├── tool/              — 工具系统基础（AgentTool 接口、ToolRegistry、ToolCall、ToolResult）
├── util/              — 工具类（ID 生成、JSON、日志脱敏）
├── webimport/         — 仅保留 MediaWikiApiClient（唯一引用者：MediaWikiSearchTool）
├── webui/             — Web UI + HTTP API 层（WebUiServer：JDK 内嵌 HttpServer，端口 8710）
│   ├── handlers/      — 7 个 handler（Agent/Chat/Llm/Timeline/World/Page/Static + HandlerUtils）
│   └── (resources)    — 前端静态文件（app.js、chat-renderer.js、session-ws.js 等）
└── worldinfo/         — WorldInfo 结构化元素存储
    ├── loader/        — NodeLoader、StateManager（持久化）
    └── tool/          — WorldInfo 工具（query_node/checkpoint/keyword/element、
                          write_element、create_checkpoint、node_* 等 14 个工具）
```

### 已废弃/不存在的包

以下包在旧版 CLAUDE.md 中列出但实际不存在（功能已被其他模块替代）：
- `campaign/` → 被 `worldinfo/` + `session/SessionNode` 替代
- `task/` → 任务跟踪由事件系统承载（`event/GSimEvent` 的 `taskId`）
- `timeline/` → 未实现
- `world/` → 被 `worldinfo/` 替代
- `storage/` → 持久化由各模块自行管理（WorldInfo JSON 文件、Cache 文件、Doc 文件）
- `chroma/` → ChromaDB 集成未实现（当前使用本地文件搜索 + MediaWiki API）

## 运行命令

模块：`gsim-lib`（核心库）、`gsim-app`（CLI/启动入口，打 fat JAR）、`gsimap`（地图服务，端口 8711）。`gsim-agent` 模块已在 Phase 1 删除（Phase 2 重建）。

```bash
# 构建（始终 clean 避免增量编译陷阱）
mvn clean package -DskipTests

# 运行（默认模式：CLI REPL + Web GUI(8710) + Map UI(8711)）
java -jar gsim-app/target/gsim-app-*.jar

# 运行 MCP 模式（--no-cli：MCP HTTP 服务 8720 + Web GUI(8710) + Map UI(8711)）
java -jar gsim-app/target/gsim-app-*.jar --no-cli

# 测试（始终 clean 避免增量编译陷阱）
mvn clean test

# 完整质量门（始终 clean 避免增量编译陷阱）
mvn clean verify --batch-mode

# 首次启动前清理运行时数据以验证自动初始化（只影响 worlds/ caches/ logs/ llms.json）
rm -rf worlds/ caches/ logs/ llms.json && java -jar gsim-app/target/gsim-app-*.jar
```

## 配置系统

### 应用配置（gsim.properties）

配置文件与数据布局（实际状态）：

- `gsim.properties` — 应用配置（AppConfig），位于当前工作目录（CWD）或 `--config <path>` 指定路径，可通过环境变量覆盖
- `llms.json` — LLM provider 配置（baseDir，即应用工作目录）
- `agents/*.json` — Agent 配置（baseDir）
- `worlds/` — 世界数据（baseDir）

注：`data/` 目录布局（`data/gsim.properties` 等）为历史文档错误（实际仅存 JLine 历史），Phase 3 将迁移到统一 `config/` 目录。

| 属性 | 默认值 | 说明 |
|------|--------|------|
| `app.name` | GSimulator | 应用名称 |
| `app.version` | 1.0.0 | 版本号 |
| `api.host` | 127.0.0.1 | API 监听地址 |
| `api.port` | 8710 | API 监听端口 |
| `api.enabled` | false | 是否启用 HTTP API |
| `llm.default_provider` | base | 默认 LLM provider ID |
| `agent.max_tool_rounds` | 64 | Agent ToolLoop 最大轮数 |

### LLM Provider 配置

Provider 配置存储在 `llms.json`（baseDir，自动生成模板），支持多个 provider：

```json
{
  "providers": [
    {
      "id": "base",
      "name": "Default Provider",
      "baseUrl": "https://api.deepseek.com/v1",
      "apiKey": "${LLM_API_KEY}",
      "model": "deepseek-v4-pro",
      "temperature": 0.3,
      "maxTokens": 2048,
      "contextWindow": 128000
    }
  ]
}
```

环境变量：
- `LLM_BASE_URL` — 覆盖 base provider 的 API 端点
- `LLM_API_KEY` — API 密钥
- `LLM_MODEL` — 模型名称
- `LLM_TEMPERATURE` — 温度参数（默认 0.3）

Provider 配置可通过 CLI（`/llm` 命令）和 HTTP API 运行时管理。

## Agent 系统

### 架构概览

Agent 系统的核心是 **ToolLoop** 模式 — LLM 在循环中调用工具，直到显式调用 `finish_action` 结束。live ToolLoop 唯一入口：`AbstractAgent.executeToolLoop()`（Phase 1 已删除 OrchestratorAgent 中的 runToolLoop/runSimToolLoop 死循环）。

```
用户输入 → AbstractAgent.executeToolLoop()
  ├── 构建 system prompt（来自 AgentConfig）
  ├── 注入工具定义（按 ToolFilterConfig + ToolGroup 过滤）
  ├── while (round <= maxRounds):
  │   ├── LLM 调用（流式或非流式）
  │   ├── 解析 tool calls（API 原生优先 → 文本 JSON fallback）
  │   ├── 执行工具（经 ToolExecutionPolicy 门禁）
  │   ├── 工具结果反馈给 LLM
  │   └── finish_action → 验证 → 返回最终文本
  └── 返回 AgentResult（含每轮 AgentRound 记录）
```

### Agent 类型

| Agent | 配置 | 工具权限 | 用途 |
|-------|------|---------|------|
| `orchestrator` | maxRounds=64, temp=0.3 | all（全部工具） | 主控 Agent，可派发 SubAgent |
| `sim` | maxRounds=16, temp=0.5 | read_only（只读+控制） | 推演叙事生成 |
| `search` | maxRounds=16, temp=0.3 | read_only（只读+控制） | 多源资料搜索 |

可通过 `agents/*.json` 添加自定义 Agent 类型。

### Agent 配置（AgentConfig）

```json
{
  "agentId": "orchestrator",
  "llmProvider": "base",
  "staticSystemPrompt": "完整的系统提示词...",
  "toolFilter": { "mode": "all" },
  "maxToolRounds": 32,
  "temperature": 0.3,
  "maxTokens": 2048
}
```

- `staticSystemPrompt` — 静态系统提示词（直接写入 JSON，不再使用 FreeMarker）
- `toolFilter.mode` — `all` / `read_only` / `custom`（allow/deny 列表）
- 配置可通过 `AgentConfigManager` 运行时热更新

### 核心类

| 类 | 职责 |
|----|------|
| `AbstractAgent` | 统一 ToolLoop 基类（655 行），子类通过钩子扩展 |
| `OrchestratorAgent` | 主协调者（405 行，Phase 1 已删 runToolLoop/runSimToolLoop 死循环），覆盖权限门禁、工具组管理、流式处理、finish_action 验证 |
| `AgentFactory` | 根据 AgentConfig 创建实例，管理 SubAgent 生命周期（派发/收集/取消） |
| `AgentConfigStore` | 从 `agents/*.json` 加载配置（含 classpath fallback），支持 reload |
| `AgentConfigManager` | 运行时 CRUD（list/get/update field），原子写入 + 自动 reload |

### SubAgent 机制

- Orchestrator 通过 `dispatch_sub_agent` 工具派发 SubAgent
- SubAgent 在虚拟线程中**同步阻塞**执行（120s 超时），结果直接作为工具反馈返回
- 每个 SubAgent 自动保存对话缓存到 `worlds/{worldId}/caches/`
- 支持 `cacheId` 参数续接之前的 SubAgent 上下文
- ESC 取消会传播到所有运行中的 SubAgent

## 工具系统

### 工具接口

```java
public interface AgentTool {
    String name();
    String description();
    ToolResult execute(ToolCall call);
    Map<String, Object> getParameters();  // JSON Schema，null = 宽 schema
}
```

所有工具通过 `ToolRegistry` 注册，LLM 通过 API 原生 `tool_calls` 调用（不支持时 fallback 到文本 JSON 解析）。

### 工具分类（ToolCategory）

| 分类 | 说明 | 门禁规则 |
|------|------|---------|
| `READ_ONLY` | 只读查询 | 直接允许 |
| `MUTATING` | 写入/修改 | CLI 模式首次需用户确认（Y/N/A） |
| `DESTRUCTIVE` | 破坏性操作 | 永远需要确认，不允许"本轮全部允许" |
| `CONTROL` | 流程控制 | 直接允许（finish_action、activate_tool_groups） |

分类映射硬编码在 `ToolCategoryRegistry` 中。

### 工具组（ToolGroup）

工具按功能分为 5 个组，通过 `activate_tool_groups` 按需激活：

| 工具组 key | 说明 | 成员工具 |
|-----------|------|---------|
| `world_info` | WorldInfo 元素读写 | query_node, query_checkpoint, query_keyword, query_element, write_element, create_checkpoint |
| `node_mgmt` | 节点管理 | node_list, node_status, node_create, node_switch, node_goto_parent |
| `import_doc` | 导入文档浏览 | import_document_list, import_document_read, import_document_search |
| `search` | 多源搜索 | wiki_search, mediawiki_search |
默认工具（无需激活）：finish_action, activate_tool_groups, dispatch_sub_agent, collect_sub_agent_results, 以及 SubAgent 缓存管理和 World/Doc 基础工具。

### 工具调用提取

`ToolCallExtractor` 从 LLM 文本输出中解析工具调用，支持：
- 纯 JSON：`{"tool":"...","args":{...}}`
- Fenced code block：` ```json\n{"tool":"...","args":{...}}\n``` `
- 波浪线 fence：`~~~json\n{...}\n~~~`
- 内联 fence：` ```json{"tool":"..."}``` `
- 混合文本 + JSON
- 多工具调用（按出现顺序提取，去重 fenced + bare JSON）

### finish_action 验证

`finish_action` 是 Agent 结束每轮工作的唯一方式。系统对 finish_action.message 执行多层验证：
- 禁止 `[工具调用已执行]` 占位符
- 禁止 `[工具结果]` / `[TOOL_RESULT]` 伪造标记
- 禁止 fenced JSON 和裸 JSON tool call
- 禁止 `{key=value}` 伪造工具输出（MODEL_FAKE_TOOL_RESULT 检测）
- 验证失败 → 打回 LLM 重写，不消耗额外轮次配额

## WorldInfo / Node 系统

### 概念模型

- **Root** — 一个独立世界观/剧本。`worlds/{worldId}/` 下的完整数据目录
- **Node（节点）** — 分支链上的一个回合/状态快照。从 n0000（根节点）开始，通过 `node_create` 延伸
- **Checkpoint（检查点）** — 节点内的分类容器（如 `worldview`、`characters`、`factions`、`player.*`）
- **Element（信息单元）** — `nodeId:checkpointId:key` 寻址的键值对，支持 tags、links、type 元数据

### 核心工具（14 个）

| 工具 | 分类 | 用途 |
|------|------|------|
| `query_node` | READ | 查看某节点的全部检查点和元素 |
| `query_checkpoint` | READ | 查看检查点在整条链上的历史（支持 `player.*` 通配） |
| `query_keyword` | READ | 全文关键词搜索（支持分页、按 checkpointId 过滤） |
| `query_element` | READ | 按 ref 精确查询单个元素（含 links 解析） |
| `write_element` | MUTATING | 写入/更新元素（默认 upsert，mode=append 追加） |
| `create_checkpoint` | MUTATING | 显式创建检查点（带 label/type 元数据） |
| `node_list` | READ | 列出当前链所有节点（flat/tree） |
| `node_status` | READ | 当前活跃节点详情 |
| `node_create` | MUTATING | 创建子节点并自动切换（必填 worldTime） |
| `node_switch` | MUTATING | 切换到链内已有节点 |
| `node_goto_parent` | MUTATING | 返回父节点 |
| `world_list` | READ | 列出所有 World |
| `world_create` | MUTATING | 创建新 World |
| `world_switch` | MUTATING | 切换活跃 World |

## 缓存系统（Cache）

SubAgent 对话缓存存储在 `worlds/{worldId}/caches/` 下，每个缓存文件为 JSON 格式：

- `CacheSession` — 缓存数据模型（sessionId、messages 列表）
- `CacheStore` — 文件读写（load、createNew、appendAndSave）
- `CachesManager` / `FileSystemCachesManager` — 缓存列表、查看、压缩
- 缓存压缩（compact）— 对长对话生成递进式摘要

相关工具：`list_sub_agent_caches`、`view_sub_agent_cache`、`view_sub_agent_output`、`compact_cache`

## HTTP API

### 启动方式

HTTP 层由 `webui/` 的 `WebUiServer`（JDK 内嵌 `HttpServer`）提供，端口 8710（`webui.port`），随应用启动常驻；Map UI 端口 8711（gsimap），MCP HTTP 端口 8720（`--no-cli` 模式）。

### API 列表

实际实现的 handler（`webui/handlers/` 下 7 个 + HandlerUtils）：

| Handler | 说明 |
|---------|------|
| `AgentApiHandler` | Agent 配置管理（列表/详情/更新字段/reload） |
| `ChatApiHandler` | 聊天（发送/取消/状态/会话/节点摘要/上传） |
| `LlmApiHandler` | LLM provider 配置管理（列表/详情/更新/连通性测试） |
| `TimelineApiHandler` | 时间线数据（data、nodes、node、activate 端点） |
| `WorldApiHandler` | 世界 CRUD + 世界文件读写 |
| `PageHandler` | HTML 页面（/chat、/timeline 等） |
| `StaticHandler` | 前端静态资源（/static/） |
| `HandlerUtils` | 公共请求/响应处理工具（非 handler） |

### SSE 流式事件

```
event: {type}
data: {"sessionId":"...","taskId":"...","type":"...","..."}

```

支持的事件类型：`command_started`, `command_done`, `command_error`, `log`, `run_stage`,
`import_progress`, `search_progress`, `tool_started`, `tool_done`, `tool_error`,
`llm_started`, `llm_delta`, `llm_reasoning_delta`, `llm_done`, `result`, `done`

### 事件过滤

- `GSimEvent` 包含 sessionId、taskId、type、time、data
- `EventSink` 通过 `accepts(GSimEvent)` 实现过滤
- `SseEventSink` / `FilteredEventSink` 按 sessionId + taskId 过滤
- CLI 和 HTTP 共用 `EventBus`

## Session 管理

- `SessionPool` — 管理 `sessionId → SessionNode` 映射，每个 session 独立的节点导航状态
- `SessionPoolBridge` — 将 session 操作桥接到 EventBus 和 Agent 生命周期
- `SessionNode` — session 内的节点上下文（含 activeNodeId、chain 等）
- `CliNodeRenderer` — CLI 节点信息渲染

## Web UI

Web UI 由 `WebUiServer`（JDK 内嵌 `HttpServer`，端口 8710）提供：
- 前端：原生 JS（`app.js`、`chat-renderer.js`、`session-ws.js`、`client-cache.js`、`message-store.js`）
- 模板：`webui/templates/` 下的 HTML 片段
- WebSocket 连接：`session-ws.js` 管理实时通信
- 面板：chat、CLI、knowledge、node detail、scenario manager、search、settings、timeline

## Prompt 管理

### 实际存储位置

- `resources/gsim/prompts/` — Markdown 格式的 prompt 文件：
  - `orchestrator-system.md` — Orchestrator 系统提示词（~300 行，含工具调用规则、WorldInfo/Node/Doc/Import 使用说明）
  - `orchestrator-world-state.md` — 世界状态注入模板
  - `sim/system.md` + `sim/user.md` — SimAgent prompt
  - `search/system.md` + `search/user.md` — SearchAgent prompt
- `resources/gsim/agents/*/config.json` — `staticSystemPrompt` 字段内嵌完整系统提示词（生产环境使用此版本）

注：`PromptManager` 类已随 `prompt/` 包删除（Phase 1），prompt 内容直接从 AgentConfig JSON 加载。

## 禁止事项

- ❌ 业务代码直接访问环境变量（走 AppConfig）
- ❌ 业务代码直接拼 HTTP 请求（走 LlmManager / LlmProvider）
- ❌ GSimulatorApplication 中写业务逻辑（只做依赖注入和启动）
- ❌ 命令类中写复杂推演逻辑（走 Agent）
- ❌ Prompt 写死在 Java 代码中（放 resources/gsim/prompts/ 或 AgentConfig JSON）
- ❌ 吞异常
- ❌ 输出 API Key
- ❌ 测试依赖外部服务（使用 FakeLlmManager）
- ❌ 静态全局可变状态

## 提交前检查清单

- 手动验收产生的测试残留文件（`worlds/default/input.md`、`worlds/default/branches/` 等）需 `git checkout` 恢复或 `rm` 清理
- `data/` 目录下的运行时文件不在版本控制中（已 gitignore `caches/`）
- 首次或测试启动前 `rm -rf worlds/ caches/ logs/ llms.json` 验证自动初始化

## 测试

- 测试数：gsim-lib 416 + gsimap 16
- 覆盖包：agent、app、cache、config、event、importing、integration、interaction、llm、mcp、session、text、tool、util、webimport、worldinfo（`prompt/`、`root/` 测试目录仅存治理类测试，主代码包已删）
- 使用 `FakeLlmManager` 实现离线测试
- 测试运行：`mvn test`
