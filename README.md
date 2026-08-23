# GSimulator

[![Java](https://img.shields.io/badge/Java-21-blue)](https://adoptium.net)
[![Maven](https://img.shields.io/badge/Maven-multi--module-C71A36)](https://maven.apache.org)
[![License](https://img.shields.io/badge/License-GPL_3.0-green)](LICENSE)

基于 Java 21 + Maven 的多 Agent 推演工作流引擎，服务于文游 / 架空历史 / 玩家行动推演场景。提供 CLI REPL、Web UI、Canvas 六角格地图编辑器、MCP 协议接入与 LLM 流式输出。

---

## 快速开始

```bash
# 环境要求: JDK 21+, Maven 3.8+
mvn package -DskipTests

# 默认 CLI 交互模式（同时启动 Web UI / Map UI / CLI WebSocket）
java -jar gsim-app/target/gsim-app-*.jar

# 无 CLI 模式：启动 MCP HTTP 服务（同时启动 Web UI / Map UI / CLI WebSocket）
java -jar gsim-app/target/gsim-app-*.jar --no-cli
```

CLI 支持的参数：

| 参数 | 说明 |
|------|------|
| `--config <path>` | 使用指定的配置文件（默认查找 `./gsim.properties`） |
| `--no-cli` | 不进入 CLI REPL，启动 MCP HTTP 服务并阻塞运行 |
| `--init-config` | 启动 LLM 配置向导并退出 |
| `--doctor` | 运行配置诊断并退出 |
| `--no-wizard` | 跳过首次运行的配置向导 |
| `--help` | 显示帮助信息 |

Claude Desktop 等 MCP 客户端可通过 stdio→HTTP 桥接接入，命令为 `./gsimap-mcp.sh`（需先以 `--no-cli` 启动 MCP HTTP 服务）。

## 服务端口

所有本地服务端口均可在 `gsim.properties` 中配置：

| 配置键 | 默认端口 | 服务 |
|--------|----------|------|
| `webui.port` | 8710 | Web UI（Thymeleaf + HTMX） |
| `map.port` | 8711 | 六角格地图编辑器 |
| `cli.ws.port` | 8712 | CLI WebSocket（CLI 文本 + Chat JSON） |
| `mcp.http.port` | 37201 | MCP HTTP（JSON-RPC 2.0，`/mcp` + `/health`） |

主要端点：

| 地址 | 说明 |
|------|------|
| `http://127.0.0.1:8710/` | Web UI：chat / timeline / scenario / settings |
| `http://127.0.0.1:8711/` | 地图编辑器 |
| `ws://127.0.0.1:8712/cli` | CLI WebSocket 文本协议 |
| `ws://127.0.0.1:8712/chat?sessionId=...` | Chat JSON 协议 |
| `http://127.0.0.1:37201/mcp` | MCP tools/list、tools/call |
| `http://127.0.0.1:37201/health` | MCP 健康检查 |

> 旧的 REST API（`/api/status`、`/api/tasks` 等）已废弃，数据读写请统一使用 MCP 工具（`gsim_*` / `gsimap_*`）。

## 配置

应用配置写入运行目录下的 `gsim.properties`，可通过 `--config` 指定其他位置；首次运行会自动生成带注释的模板。环境变量（如 `GSIMAP_PORT`）提供等价配置，但配置文件优先级更高。

常用配置键：

| 配置键 | 默认值 | 说明 |
|--------|--------|------|
| `llm.base_url` | — | OpenAI 兼容 API 端点 |
| `llm.api_key` | — | API 密钥 |
| `llm.model` | deepseek-v4-pro | 模型名称 |
| `llm.temperature` | 0.3 | 温度参数 |
| `worlds.dir` | worlds | 世界数据目录 |
| `webui.host` | 127.0.0.1 | Web UI 监听地址 |
| `webui.port` | 8710 | Web UI 端口 |
| `webui.enabled` | false | Web UI 开关（CLI 启动时强制开启） |
| `map.port` | 8711 | Map UI 端口 |
| `cli.ws.port` | 8712 | CLI WebSocket 端口 |
| `mcp.http.port` | 37201 | MCP HTTP 端口 |
| `agent.tool_loop.max_rounds` | 64 | Agent ToolLoop 最大工具轮数 |
| `context.session.history.turns` | 12 | 对话上下文保留轮数 |
| `llm.stream.enabled` | true | LLM 流式输出开关 |
| `agent.tool_loop.result_inline_max_chars` | 4000 | Agent ToolLoop 工具结果内联上限（超限暂存或截断） |
| `agent.tool_loop.result_staging.enabled` | true | 工具结果超限自动暂存为 TMP 文档（返回 docId） |
| `mcp.response.max_json_bytes` | 50000 | MCP 响应 JSON 最大字节数（超限触发截断/暂存） |
| `mcp.response.snippet_max_chars` | 300 | MCP 响应单条 snippet 截断上限 |
| `mcp.response.default_page_size` | 20 | MCP 列表分页默认页大小 |
| `mcp.response.max_page_size` | 100 | MCP 列表分页最大页大小 |
| `mcp.response.overflow_staging.enabled` | true | MCP 响应超限自动暂存开关 |
| `mcp.response.overflow_staging.threshold` | 500 | MCP 响应溢出暂存触发阈值（snippet 字符数超过即暂存为 TMP 文档） |
| `core.doc.query.staging.threshold` | 3000 | `query_*` 返回元素暂存阈值（字符数超过即暂存为 TMP 文档，返回 docId） |
| `core.doc.tmp.max_age_hours` | 168 | TMP 文档最大保留时长（小时），启动/暂存前自动清扫 |
| `core.doc.tmp.cleanup_enabled` | true | TMP 文档自动清扫开关 |
| `docs.dir` | — | 文档目录（未设置时默认 worlds 上级目录下 `docs`） |
| `caches.dir` | — | SubAgent 缓存目录（未设置时默认 worlds 上级目录下 `caches`） |
| `knowledge.db.path` | — | 知识库 SQLite 数据库路径（未设置时默认 `data/knowledge/gsim.db`） |
| `embedding.timeout_connect_seconds` | 30 | Embedding 连接超时（秒） |
| `embedding.timeout_read_seconds` | 60 | Embedding 读取超时（秒） |
| `embedding.timeout_write_seconds` | 30 | Embedding 写入超时（秒） |
| `agent.subagent.collect.timeout_seconds` | 300 | SubAgent 结果收集超时（秒） |
| `agent.subagent.max_completed` | 100 | 已完成 SubAgent 结果缓存上限 |
| `import.doc.max_full_read_chars` | 30000 | 导入文档全量读取最大字符数 |
| `import.doc.default_limit` | 8000 | 导入文档默认读取上限（字符数） |
| `web_research.wiki.url` | https://en.wikipedia.org/w/api.php | MediaWiki 搜索 API 端点 |
| `map.radius.default` | 80 | 地图默认查询半径 |
| `map.cache.max_entries` | 32 | 地图服务 LRU 缓存条目上限 |
| `map.contour.cache.max` | 5000 | 等高线查询缓存上限 |
| `map.lasso.max_radius` | 200 | Lasso 框选最大半径 |
| `map.lasso.max_fill` | 30000 | Lasso 填充最大格数 |
| `map.compression.min_region_size` | 100 | 地图压缩最小区域大小 |
| `map.resolver.max_chain_depth` | 200 | 地图引用解析最大链深 |

> **溢出暂存**：工具结果 / MCP 响应超过内联上限时自动暂存为 TMP 文档（`docs/tmp/*.md`），响应中返回 docId，全文可通过 `gsim_doc_read` 或 `@doc:` 引用读取。`core.doc.tmp.*` 控制 TMP 文档的自动清扫。

LLM Provider（base URL / key / model / temperature / extra_body 等）在 `llms.json` 中管理，支持运行时热更新。首次运行向导会创建该文件。

## 核心特性

1. **Agent ToolLoop** — LLM 在循环中调用工具直至 `finish_action`；Orchestrator 协调 SubAgent（sim / search）在虚拟线程中并行工作，工具执行带权限门禁与工具组按需激活。

2. **多模态 Transport** — CLI REPL（JLine）、Web UI（HTMX + Tailwind + Thymeleaf）、CLI WebSocket（实时流式节点）、MCP HTTP（JSON-RPC 2.0）。

3. **WorldInfo 知识库** — 三层模型（Node → Checkpoint → Element），tag 索引 + 关键词倒排索引，节点分支与回放，wildcard 检查点查询（`player.*`）。

4. **GSimap 六角格地图** — 程序化地形生成、Canvas 地图编辑器、省/城/地形/通路编辑、diff-chain 历史回溯，以及 `gsimap_*` MCP 工具集。

5. **统一世界读取入口** — `WorldManager` 集中负责 `worlds/` 目录的只读访问与活跃节点推导，应用启动、MCP 工具、MapService 均通过它读取世界数据。

## 模块结构

| 模块 | 说明 |
|------|------|
| `gsim-agentlib` | AgentTool 协议、ToolRegistry、MCP 适配层，零业务依赖 |
| `gsim-core` | 核心业务库 — WorldInfo、DocStore、LLM、缓存、会话、事件、配置、导入 |
| `gsim-agent` | Agent 运行时（ToolLoop / Orchestrator / SubAgent）与全部工具实现 |
| `gsim-map` | 六角格地图服务与编辑器（MapService、diff 链、HTTP API、Canvas 前端） |
| `gsim-app` | 程序入口 + 应用组装 + CLI/WebUI 交互壳，产出 shaded fat JAR |

依赖方向：`gsim-app → gsim-agent / gsim-map / gsim-core / gsim-agentlib`，`gsim-agent → gsim-core / gsim-agentlib / gsim-map`，`gsim-map → gsim-core`。

## 文档

本目录其他参考文件：

- `CLAUDE.md` — AI Agent 开发指南（架构原则、包结构、运行命令、配置系统、提交清单）
- `AGENTS.md` — 外部 Agent（Claude Desktop、Windsurf 等）接入 MCP 配置

详细开发者文档见 `docs/`：

| 文档 | 说明 |
|------|------|
| `docs/ARCHITECTURE.md` | 系统架构总览 |
| `docs/MODULES.md` | 模块职责与包结构 |
| `docs/RUNTIME-FLOWS.md` | 关键运行时流程 |
| `docs/DATA-MODEL.md` | 核心数据结构 |
| `docs/TOOL-CONTRACTS.md` | 工具接口契约 |
| `docs/TESTING.md` | 测试策略与质量门禁 |
| `docs/DEVELOPMENT.md` | 开发环境与工作流 |
| `docs/adr/` | 架构决策记录 |

## 许可证

GPL 3.0 — 详见 [LICENSE](LICENSE)。
