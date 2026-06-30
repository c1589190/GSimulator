# GSimulator — Claude 开发指南

## 项目简介

GSimulator 是一个基于 Java 21 + Maven 的多 Agent 推演工作流引擎，服务于"文游 / 架空历史 / 玩家行动推演"场景。它不是普通聊天机器人，而是一个可审计、可回放、可扩展的回合制推演系统。

支持 CLI REPL（JLine）、HTTP API + SSE 流式事件输出、Web UI（Javalin 内嵌静态服务）。

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
├── api/               — HTTP API 层（Javalin 路由、SSE）
│   ├── handlers/      — 19 个 API handler（Status、Tasks、Command、StreamCommand、
│   │                     Config、Compact、Experiences、Help、Import、LogsOutputs、
│   │                     Messages、Pins、Players、Roots、Save、Skills、Tools、Where）
│   └── dto/           — API 请求/响应 DTO
├── cache/             — SubAgent 对话缓存（CacheSession、CacheStore、CachesManager）
├── commands/          — CLI 命令实现（AgentCommand、ChatCommand、CompactCommand、
│                         LlmCommand、NodeCommand、WorldCommand）
├── compact/           — 缓存压缩（摘要生成）
├── config/            — AppConfig（gsim.properties + 环境变量）
├── crawler/           — 联网搜索和网页抓取基础设施
├── event/             — 统一事件系统（EventBus、GSimEvent、ConsoleEventSink、SseEventSink）
├── importdata/        — 资料导入管道（数据模型）
├── importing/         — 导入工具实现（import_document_list/read/search）
│   └── tool/          — 导入工具 AgentTool 封装
├── interaction/       — 交互层（CLI REPL、CommandParser、ConsoleInteractionAdapter）
├── llm/               — LLM 客户端统一封装
│   │                     LlmManager（统一入口）、LlmProviderRegistry（多 provider）、
│   │                     LlmCall（异步提交）、StreamPool（流式缓冲）、
│   │                     LlmConfigManager（provider 配置管理）、
│   │                     JsonLlmService（OpenAI-compatible HTTP 实现）
├── output/            — 输出格式化（Markdown / JSON / Console）
├── prompt/            — Prompt 模板管理（PromptManager、PromptTemplate）
│                         注：当前 PromptManager 为轻量实现，实际 prompt 内容存储在
│                         resources/gsim/prompts/*.md 和 Agent config JSON 中
├── resource/          — 资源文件管理（ResourceManager）
├── root/              — Root Workspace 管理（根节点初始化、bootstrap）
├── session/           — Session 管理（SessionPool、SessionNode、SessionPoolBridge）
├── skill/             — Skill 系统（文件化技能管理）
│   └── tool/          — Skill 工具（skill_list/read/create/write/search/index，6 个工具）
├── tool/              — 工具系统基础（AgentTool 接口、ToolRegistry、ToolCall、ToolResult）
├── util/              — 工具类（ID 生成、JSON、日志脱敏）
├── webimport/         — 网页抓取管道（URL → HTML → txt → import）
│                         含 MediaWiki API 客户端、HTML 提取、限速器
├── webui/             — Web UI（Javalin 内嵌静态服务）
│   ├── handlers/      — Web UI 专用 API handler
│   └── (resources)    — 前端静态文件（app.js、chat-renderer.js、session-ws.js 等）
└── worldinfo/         — WorldInfo 结构化元素存储
    ├── loader/        — NodeLoader、StateManager（持久化）
    └── tool/          — WorldInfo 工具（query_node/checkpoint/keyword/element、
                          write_element、create_checkpoint、node_* 等 14 个工具）
```

### 已废弃/不存在的包

以下包在旧版 CLAUDE.md 中列出但实际不存在（功能已被其他模块替代）：
- `campaign/` → 被 `worldinfo/` + `root/` + `session/SessionNode` 替代
- `task/` → 任务管理分散在 `api/handlers/TasksApiHandler` 和 `session/` 中
- `timeline/` → 未实现
- `world/` → 被 `worldinfo/` 替代
- `storage/` → 持久化由各模块自行管理（WorldInfo JSON 文件、Cache 文件、Skill 文件）
- `chroma/` → ChromaDB 集成未实现（当前使用本地文件搜索 + MediaWiki API）

## 运行命令

```bash
# 构建
mvn package -DskipTests

# 运行（默认 CLI 模式）
java -jar target/GSimulator.jar

# 仅 HTTP API
java -jar target/GSimulator.jar --http

# CLI + HTTP API
java -jar target/GSimulator.jar --cli --http

# 测试
mvn test

# 首次启动前清理 data/ 以验证自动初始化
rm -rf data/ && java -jar target/GSimulator.jar
```

## 配置系统

### 应用配置（gsim.properties）

配置文件位于 `data/gsim.properties`（自动生成，可通过环境变量覆盖）：

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

Provider 配置存储在 `data/llms.json`（自动生成模板），支持多个 provider：

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

Agent 系统的核心是 **ToolLoop** 模式 — LLM 在循环中调用工具，直到显式调用 `finish_action` 结束。

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

可通过 `data/agents/*.json` 添加自定义 Agent 类型。

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
| `AbstractAgent` | 统一 ToolLoop 基类（~500 行），子类通过钩子扩展 |
| `OrchestratorAgent` | 主协调者（~1460 行），覆盖权限门禁、工具组管理、流式处理、finish_action 验证 |
| `AgentFactory` | 根据 AgentConfig 创建实例，管理 SubAgent 生命周期（派发/收集/取消） |
| `AgentConfigStore` | 从 `data/agents/*.json` 加载配置（含 classpath fallback），支持 reload |
| `AgentConfigManager` | 运行时 CRUD（list/get/update field），原子写入 + 自动 reload |

### SubAgent 机制

- Orchestrator 通过 `dispatch_sub_agent` 工具派发 SubAgent
- SubAgent 在虚拟线程中**同步阻塞**执行（120s 超时），结果直接作为工具反馈返回
- 每个 SubAgent 自动保存对话缓存到 `data/worlds/{worldId}/caches/`
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
| `skill_mgmt` | Skill 管理 | skill_list, skill_read, skill_create, skill_write, skill_search, skill_index |

默认工具（无需激活）：finish_action, activate_tool_groups, dispatch_sub_agent, collect_sub_agent_results, 以及 SubAgent 缓存管理和 World/Skill 基础工具。

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

- **Root** — 一个独立世界观/剧本。`data/worlds/{worldId}/` 下的完整数据目录
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

## Skill 系统

Skill 是文件化的技能/知识模块，存储在 `data/skills/` 下。每个 Skill 是一个文件夹，包含 `SKILL.md` 文件。

### Skill 工具（6 个）

| 工具 | 用途 |
|------|------|
| `skill_list` | 列出所有已安装的 Skill |
| `skill_read` | 分段读取 Skill 内容（支持 offset/limit） |
| `skill_create` | 创建新 Skill（文件夹 + SKILL.md） |
| `skill_write` | 修改 Skill 内容（替换/追加/覆盖） |
| `skill_search` | 语义搜索 Skill（基于 embedding 向量） |
| `skill_index` | 为 Skill 建立/更新语义索引 |

### 模板系统

`resources/gsim/templates/` 下包含多个引导模板：`world-template.md`、`input-template.md`、`simulation-method.md`、`skill-system-template.md` 等，用于初始化新 World 和 Skill。

## 缓存系统（Cache）

SubAgent 对话缓存存储在 `data/worlds/{worldId}/caches/` 下，每个缓存文件为 JSON 格式：

- `CacheSession` — 缓存数据模型（sessionId、messages 列表）
- `CacheStore` — 文件读写（load、createNew、appendAndSave）
- `CachesManager` / `FileSystemCachesManager` — 缓存列表、查看、压缩
- 缓存压缩（compact）— 对长对话生成递进式摘要

相关工具：`list_sub_agent_caches`、`view_sub_agent_cache`、`view_sub_agent_output`、`compact_cache`

## HTTP API

### 启动方式

```bash
java -jar target/GSimulator.jar --http          # 仅 HTTP API
java -jar target/GSimulator.jar --cli --http    # CLI + HTTP API
```

### API 列表

实际实现的 handler（`api/handlers/` 下 19 个文件）：

| Handler | 说明 |
|---------|------|
| `StatusApiHandler` | GET /api/status — 应用状态 |
| `TasksApiHandler` | POST/GET /api/tasks — 任务创建/列表/状态/取消 |
| `CommandApiHandler` | POST /api/command — 执行 CLI 命令 |
| `StreamCommandHandler` | POST /api/command/stream — SSE 流式命令 |
| `ConfigApiHandler` | GET/POST /api/config — 配置管理 |
| `CompactApiHandler` | POST /api/compact — 缓存压缩 |
| `ExperiencesApiHandler` | GET/POST /api/experiences — 经验管理 |
| `HelpApiHandler` | GET /api/help — 帮助信息 |
| `ImportApiHandler` | POST /api/import — 资料导入 |
| `LogsOutputsApiHandler` | GET /api/logs /api/outputs — 日志/输出 |
| `MessagesApiHandler` | GET/POST /api/messages — 消息管理 |
| `PinsApiHandler` | GET/POST /api/pins — 固定信息 |
| `PlayersApiHandler` | GET/POST /api/players — 玩家管理 |
| `RootsApiHandler` | GET/POST /api/roots — Root 管理 |
| `SaveApiHandler` | POST /api/save — 保存 |
| `SkillsApiHandler` | GET/POST /api/skills — Skill 管理 |
| `ToolsApiHandler` | GET /api/tools — 工具列表 |
| `WhereApiHandler` | GET /api/where — 当前位置/上下文 |

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

Web UI 通过 Javalin 内嵌静态服务提供：
- 前端：原生 JS（`app.js`、`chat-renderer.js`、`session-ws.js`、`client-cache.js`、`message-store.js`）
- 模板：`webui/templates/` 下的 HTML 片段
- WebSocket 连接：`session-ws.js` 管理实时通信
- 面板：chat、CLI、knowledge、node detail、scenario manager、search、settings、timeline

## Prompt 管理

### 实际存储位置

- `resources/gsim/prompts/` — Markdown 格式的 prompt 文件：
  - `orchestrator-system.md` — Orchestrator 系统提示词（~300 行，含工具调用规则、WorldInfo/Node/Skill/Import 使用说明）
  - `orchestrator-world-state.md` — 世界状态注入模板
  - `sim/system.md` + `sim/user.md` — SimAgent prompt
  - `search/system.md` + `search/user.md` — SearchAgent prompt
- `resources/gsim/agents/*/config.json` — `staticSystemPrompt` 字段内嵌完整系统提示词（生产环境使用此版本）

### PromptManager

`PromptManager` 为轻量实现，支持 `{{variable}}` 模板变量替换。实际 prompt 渲染在 `AgentConfig.renderUserPrompt()` 中完成。不再使用 FreeMarker。

注：`PromptManager.loadAll()` 当前为占位实现，prompt 直接从 AgentConfig JSON 加载。

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

- 手动验收产生的测试残留文件（`data/worlds/default/input.md`、`data/worlds/default/branches/` 等）需 `git checkout` 恢复或 `rm` 清理
- `data/` 目录下的运行时文件不在版本控制中（已 gitignore `caches/`）
- 首次或测试启动前 `rm -rf data/` 验证自动初始化

## 测试

- 75 个测试文件，覆盖 agent、api、cache、config、event、importing、interaction、llm、prompt、root、session、tool、util、webimport、worldinfo
- 使用 `FakeLlmManager` 实现离线测试
- 测试运行：`mvn test`
