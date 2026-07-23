# GSimulator 模块说明

> 本文档定义三模块（`gsim-lib`、`gsimap`、`gsim-app`）的职责边界与包结构。
> 体系架构参见 `ARCHITECTURE.md`，核心数据结构参见 `DATA-MODEL.md`，AI Agent 开发指南参见 `CLAUDE.md`。

---

## 1. Module Dependency

| Module | Source Files | Depends On | Responsibility |
|--------|-------------|------------|----------------|
| `gsim-lib` | ~395 | Jackson, OkHttp, JLine, Jsoup, Thymeleaf, SQLite | Core engine — Agent system, LLM, WorldInfo, Tools, MCP, HTTP API, Web UI, DocStore, Import pipeline, Session/Event infrastructure |
| `gsimap` | ~52 | `gsim-lib`, Jackson | Hex map editor — MapData model, terrain generation/editing, map persistence, 20 MCP tools, standalone HTTP server, Canvas browser editor |
| `gsim-app` | 1 | `gsim-lib`, `gsimap` | Thin CLI entry point — `Main.java`, three-phase startup (Config → Assembly → Transport) |

### Module Descriptions

- **gsim-lib**: All core application logic. Agent ToolLoop, SubAgent dispatch/collect, multi-provider LLM management with streaming, WorldInfo/Node/Checkpoint persistence via JSON files, 70+ tools registered through ToolRegistry, MCP server (JSON-RPC 2.0 over stdio), HTTP API (Javalin, 19 handlers + SSE), Web UI (Thymeleaf + HTMX + Tailwind), DocStore for skill/experience/document management, import pipeline (document list/read/search), Session pool with per-session node navigation, EventBus with Console/Sse sinks. Compiles to an embeddable JAR.

- **gsimap**: Hex map subsystem. `MapData` model (HexCell, Province, PathwayGroup, CompressedRegion), procedural terrain generation (`MapGenerator`), terrain editing (`TerrainCanvas`), map persistence and diff-chain resolution (`MapStore`, `MapResolver`, `MapDiff`), 20 MCP tools registered via `GsimapToolRegistrar`, standalone HTTP server on port 8711 (`GsimapHttpServer`), Canvas-based browser map editor (14 vanilla JS modules). Depends on `gsim-lib` for the `AgentTool` interface, `ToolRegistry`, and MCP infrastructure.

- **gsim-app**: Single `com.gsim.Main` class. Three-phase startup: `ConfigLoader` reads properties → `Bootstrap` initializes WorldInfo and caches → `GSimulatorApplication` wires dependency injection and starts services. Wires gsimap tools via `GsimapToolRegistrar.registerAll()`. Default mode is CLI REPL (JLine); `--no-cli` runs MCP stdio server. Shaded into an executable fat JAR.

---

## 2. gsim-lib Package Map

| Package | Description |
|---------|-------------|
| `com.gsim.agent.core` | Agent core: `AbstractAgent` ToolLoop, `AgentFactory`, `AgentResult`, `AgentRound` |
| `com.gsim.agent.config` | Agent config runtime CRUD (`AgentConfigManager`, `AgentConfigStore`) |
| `com.gsim.agent.tool` | Agent management tools (`dispatch_sub_agent`, `finish_action`, etc., 11 tools) |
| `com.gsim.agent.management` | `AgentCacheStore`, `AgentsManager`, `AgentSseManager` |
| `com.gsim.api` | HTTP API layer: Javalin routes, SSE infrastructure |
| `com.gsim.api.handlers` | 19 API handlers (Status, Tasks, Command, StreamCommand, Config, Compact, etc.) |
| `com.gsim.api.dto` | API request/response DTOs |
| `com.gsim.app` | Application startup and DI (`GSimulatorApplication`, `ApplicationContext`) |
| `com.gsim.cache` | SubAgent dialog cache (`CacheSession`, `CacheStore`, `CachesManager`) |
| `com.gsim.commands` | CLI command implementations (Agent, Chat, Compact, Llm, Node, World) |
| `com.gsim.compact` | Cache compaction (progressive summarization) |
| `com.gsim.config` | `AppConfig` — `gsim.properties` with environment variable override |
| `com.gsim.crawler` | Web search and page fetch infrastructure |
| `com.gsim.doc` | DocStore document management (`Document`, `DocStore`, 9 tools) |
| `com.gsim.event` | Unified event system (`EventBus`, `GSimEvent`, Console/Sse sinks) |
| `com.gsim.importdata` | Import pipeline data model |
| `com.gsim.importing` | Import tool implementation (`import_document_list` / `read` / `search`) |
| `com.gsim.interaction` | Interaction layer (CLI REPL, `ConsoleInteractionAdapter`, `CommandParser`) |
| `com.gsim.llm` | LLM client abstraction (`LlmManager`, `LlmProviderRegistry`, `JsonLlmService`) |
| `com.gsim.mcp` | MCP protocol implementation (`AbstractMcpServer`, `GsimMcpServer`, `ToolRegistryMcpAdapter`) |
| `com.gsim.output` | Output formatting (Markdown / JSON / Console) |
| `com.gsim.prompt` | Prompt template management (lightweight `PromptManager`) |
| `com.gsim.ref` | `@` reference resolution (`ResolveRefTool`) |
| `com.gsim.resource` | Resource file management (`ResourceManager`) |
| `com.gsim.root` | Root workspace management (root node bootstrap, initialization) |
| `com.gsim.session` | Session management (`SessionPool`, `SessionNode`, `SessionPoolBridge`) |
| `com.gsim.skill` | Skill storage (migrated to DocStore, compatibility shim) |
| `com.gsim.text` | Text edit pipeline (`TextEditTool`) |
| `com.gsim.tool` | Tool system foundation (`AgentTool` interface, `ToolRegistry`, `ToolCall`, `ToolResult`) |
| `com.gsim.util` | Utilities (ID generation, JSON helpers, log sanitization) |
| `com.gsim.webimport` | Web fetch pipeline (URL → HTML → txt → import, MediaWiki API client) |
| `com.gsim.webui` | Web UI (Javalin embedded static server, Thymeleaf templates, HTMX + Tailwind) |
| `com.gsim.worldinfo` | WorldInfo core model (`WorldInformation`, Node, Checkpoint, Element) |
| `com.gsim.worldinfo.loader` | Data loading (`WorldInfoBuilder`, `NodeLoader`, `ActiveStateManager`) |
| `com.gsim.worldinfo.tool` | WorldInfo tools (`query_node` / `checkpoint` / `keyword` / `element`, `write_element`, etc., 14 tools) |
| `com.gsim.worldinfo.manager` | `WorldManager` advanced CRUD |

---

## 3. gsimap Package Map

| Package | Description |
|---------|-------------|
| `com.gsimap.config` | GSimap configuration (`GsimapConfig`) |
| `com.gsimap.http` | HTTP server (`GsimapHttpServer`, `MapWebUIHandler`, `StaticFileHandler`) |
| `com.gsimap.map` | Map data model (`MapData`, `HexCell`, `Province`, `PathwayGroup`, `CompressedRegion`, etc.) |
| `com.gsimap.service` | Map services (`MapService`, `MapGenerator`, `MapResolver`, `MapDiff`, `CompressionService`, `TerrainCanvas`) |
| `com.gsimap.tool` | MCP tools (`AbstractGsimapTool`, `GsimapToolRegistrar`, 20 concrete tools) |

---

## 4. gsim-app

| Class | Module | Responsibility |
|-------|--------|----------------|
| `com.gsim.Main` | `gsim-app` | Three-phase startup: `ConfigLoader` → `Bootstrap` → `GSimulatorApplication`. Wires all modules, registers gsimap tools, starts 3 HTTP servers. Default: CLI REPL. `--no-cli`: MCP stdio server. |

---

## 5. 已废弃 / 不存在的包

The following packages were referenced in older documentation but **do not exist** in the current codebase:

| Package | Status | Replacement |
|---------|--------|-------------|
| `campaign/` | Removed | `worldinfo/` + `root/` + `session/SessionNode` |
| `task/` | Removed | Dispersed into `api/handlers/TasksApiHandler` and `session/` |
| `timeline/` | Not implemented | — |
| `world/` | Removed | `worldinfo/` |
| `storage/` | Removed | Each module manages its own persistence (WorldInfo JSON, Cache JSON, Doc files) |
| `chroma/` | Not implemented | Local file search + MediaWiki API |

---

## 6. Cross-References

- [`ARCHITECTURE.md`](ARCHITECTURE.md) — 系统架构总览（交互层 / LLM 层 / 工具层 / 持久化层）
- [`DATA-MODEL.md`](DATA-MODEL.md) — 各模块核心数据结构定义
- [`CLAUDE.md`](../CLAUDE.md) — AI Agent 开发指南，含完整 package 说明与架构原则
