# GSimulator 模块说明

> 本文档定义当前五个 Maven 模块（`gsim-agentlib`、`gsim-core`、`gsim-agent`、`gsim-map`、`gsim-app`）的职责边界与包结构。

> 体系架构参见 `ARCHITECTURE.md`，核心数据结构参见 `DATA-MODEL.md`，AI Agent 开发指南参见 `CLAUDE.md`。

---

## 1. Module Dependency

| Module | Depends On | Responsibility |
|--------|------------|----------------|
| `gsim-agentlib` | Jackson, SLF4J | AgentTool 协议、ToolRegistry、工具执行门禁、MCP 协议与 HTTP 适配 |
| `gsim-core` | 无内部模块依赖（Jackson/OkHttp/Log4j2 等外部库） | 核心业务 — WorldInfo、DocStore、LLM、缓存、会话/事件、配置、导入、引用解析 |
| `gsim-agent` | `gsim-core`, `gsim-agentlib`, `gsim-map` | Agent 运行时（ToolLoop / Orchestrator / SubAgent）与全部工具实现 |
| `gsim-map` | `gsim-core` | 六角格地图 — MapData、地形生成/编辑、diff 链、HTTP API、Canvas 前端 |
| `gsim-app` | 以上全部 | 程序入口、依赖组装、CLI 交互、WebUI、shaded fat JAR |

### Module Descriptions

- **gsim-agentlib**: 零业务依赖的协议层。`AgentTool` / `ToolCall` / `ToolResult` / `ToolRegistry` / `ToolExecutionGuard` 位于 `com.gsim.agentlib.tool`；MCP JSON-RPC 2.0 与 Streamable HTTP 适配位于 `com.gsim.agentlib.mcp`。

- **gsim-core**: 业务内核。WorldInfo 模型与节点/世界加载、DocStore、LLM Provider/Registry、缓存、会话池、事件总线、配置系统、导入管道、`@` 引用解析。**不包含** Agent 运行时与工具实现。

- **gsim-agent**: Agent 运行时与工具实现。`AbstractAgent` ToolLoop、`OrchestratorAgent`、`AgentFactory`、SubAgent 管理，以及 doc/worldinfo/map/import/search/cache/text 等全部 AgentTool 实现；`AgentBridge` 是外部宿主的工具组装入口。

- **gsim-map**: 地图服务。`MapService` 提供查询/编辑/diff 持久化，`GsimapHttpServer` 在 `map.port`（默认 8711）提供编辑器与 REST API，前端为 `web/` 下的原生 Canvas JS。

- **gsim-app**: 组装与入口。`Main` 三阶段启动（ConfigLoader → Bootstrap → GSimulatorApplication），注册全部工具、启动 WebUI（`webui.port`，默认 8710）、CLI WebSocket（`cli.ws.port`，默认 8712）、MCP HTTP（`mcp.http.port`，默认 37201）。`--no-cli` 时跳过 CLI REPL。

---

## 2. gsim-agentlib Package Map

| Package | Description |
|---------|-------------|
| `com.gsim.agentlib.mcp` | MCP 协议实现（ToolDef、McpHttpServer、ToolRegistryMcpAdapter、GsimRequestContext） |
| `com.gsim.agentlib.tool` | AgentTool / ToolCall / ToolResult / ToolRegistry / ToolExecutionGuard |
| `com.gsim.agentlib.util` | JSON 工具 |

## 3. gsim-core Package Map

| Package | Description |
|---------|-------------|
| `com.gsim.core.cache` | 对话缓存（CacheSession、CacheStore、CachesManager） |
| `com.gsim.core.compact` | 缓存压缩 |
| `com.gsim.core.config` | 配置加载与诊断（ConfigLoader、CoreConfig、ConfigWizard、ConfigDoctor） |
| `com.gsim.core.doc` | DocStore / Document 文档体系 |
| `com.gsim.core.embedding` | Embedding 客户端 |
| `com.gsim.core.event` | 事件总线与进度 Sink |
| `com.gsim.core.importing` | import 文档管道 |
| `com.gsim.core.llm` | LLM Provider / LlmManager / SSE 解析 / StreamPool |
| `com.gsim.core.ref` | `@` 引用解析 |
| `com.gsim.core.session` | SessionPool / SessionNode 统一会话节点池 |
| `com.gsim.core.skill` | 兼容旧 skills 的索引 |
| `com.gsim.core.text` | 文本编辑基础 |
| `com.gsim.core.util` | JSON / ID / 日志脱敏等工具 |
| `com.gsim.core.webimport` | MediaWiki 导入 |
| `com.gsim.core.worldinfo` | WorldInfo 模型（Node / Checkpoint / Element） |
| `com.gsim.core.worldinfo.loader` | 世界加载（WorldManager、WorldInfoBuilder、NodeLoader、ActiveStateManager） |

## 4. gsim-agent Package Map

| Package | Description |
|---------|-------------|
| `com.gsim.agent` | Agent 配置、权限门禁、ToolCall 提取、OrchestratorAgent |
| `com.gsim.agent.bridge` | AgentBridge 工具组装入口与上下文对象 |
| `com.gsim.agent.config` | Agent 配置管理 |
| `com.gsim.agent.core` | AbstractAgent ToolLoop、AgentFactory、AgentRound |
| `com.gsim.agent.management` | AgentsManager / AgentCacheStore |
| `com.gsim.agent.mcp` | GsimMcpServer |
| `com.gsim.agent.tool` | Agent 控制流工具（dispatch/collect/finish 等） |
| `com.gsim.agent.tools.*` | 领域工具：cache/doc/importing/map/ref/search/text/worldinfo |

## 5. gsim-map Package Map

| Package | Description |
|---------|-------------|
| `com.gsim.map.config` | GsimapConfig |
| `com.gsim.map.http` | GsimapHttpServer / MapWebUIHandler / StaticFileHandler |
| `com.gsim.map.map` | MapData / MapDiff / MapStore / MapResolver |
| `com.gsim.map.service` | MapService、地形生成/渲染/压缩等 |

## 6. gsim-app Package Map

| Package | Description |
|---------|-------------|
| `com.gsim` | Main 入口 |
| `com.gsim.app` | AppConfig、Bootstrap、ApplicationContext、GSimulatorApplication |
| `com.gsim.commands` | CLI 命令（chat/world/node/llm/agent/compact/board） |
| `com.gsim.interaction` | CLI 交互层 |
| `com.gsim.webui` | WebUI 服务器与 WebSocket |
| `com.gsim.webui.handlers` | chat/timeline/llm/agents/worlds 等 HTTP handler |

## 7. Cross-References

- [`ARCHITECTURE.md`](ARCHITECTURE.md) — 系统架构总览
- [`DATA-MODEL.md`](DATA-MODEL.md) — 各模块核心数据结构定义
- [`CLAUDE.md`](../CLAUDE.md) — AI Agent 开发指南，含完整 package 说明与架构原则
