# GSimulator

[![Java](https://img.shields.io/badge/Java-21-blue)](https://adoptium.net)
[![Maven](https://img.shields.io/badge/Maven-multi--module-C71A36)](https://maven.apache.org)
[![License](https://img.shields.io/badge/License-GPL_3.0-green)](LICENSE)

基于 Java 21 + Maven 的多 Agent 推演工作流引擎，服务于文游 / 架空历史 / 玩家行动推演场景。支持 CLI REPL、HTTP API + SSE 流式输出、Web UI、Canvas 六角格地图编辑器、MCP stdio 协议。

---

## 快速开始

```bash
# 环境要求: JDK 21+, Maven 3.8+
mvn package -DskipTests

java -jar target/GSimulator.jar               # CLI 交互模式
java -jar target/GSimulator.jar --no-cli      # MCP stdio 模式 (供 Claude Desktop 等接入)
java -jar target/GSimulator.jar --cli --http  # CLI + HTTP API
```

## 配置

| 变量 | 说明 | 默认值 |
|------|------|--------|
| `LLM_BASE_URL` | OpenAI 兼容 API 端点 | — |
| `LLM_API_KEY` | API 密钥 | — |
| `LLM_MODEL` | 模型名称 | deepseek-v4-pro |
| `LLM_TEMPERATURE` | 温度参数 | 0.3 |
| `API_HOST` | HTTP 监听地址 | 127.0.0.1 |
| `API_PORT` | HTTP 监听端口 | 8710 |

配置通过 `data/gsim.properties` 持久化，环境变量优先覆盖。LLM Provider 在 `data/llms.json` 中管理，支持运行时热更新。

## 核心特性

1. **Agent ToolLoop** — LLM 在循环中调用工具直至 `finish_action`。OrchestratorAgent（64 rounds）协调 SubAgent（sim / search，16 rounds）在虚拟线程中并行工作。

2. **多模态 Transport** — CLI REPL（JLine）/ HTTP API + SSE / Web UI（HTMX + Tailwind）/ MCP stdio（JSON-RPC 2.0）/ Canvas 地图编辑器（:8711）。

3. **WorldInfo 知识库** — 三层模型（Node → Checkpoint → Element），tag 索引 + 关键词全文搜索，节点分支与回放，wildcard 检查点查询（`player.*`）。

4. **GSimap 六角格地图** — 程序化地形生成（simplex noise）、Canvas 地图编辑器、省/城/地形/连通性编辑、diff-chain 历史回溯、20 个 MCP 工具。

5. **MCP 协议支持** — JSON-RPC 2.0 over stdio，70+ 工具通过 `gsim_` / `gsimap_` 前缀暴露。worldId 自动注入、分页、`@` 引用系统。

6. **SubAgent 派发** — Orchestrator 通过 `dispatch_sub_agent` 派发专业 SubAgent，独立的 ToolLoop + 权限上限，对话缓存自动持久化。

7. **工具组按需激活** — 5 个工具组（`world_info`、`node_mgmt`、`import_doc`、`search`、`docs`）通过 `activate_tool_groups` 按需激活，减少 prompt token 消耗。

8. **流式输出** — SSE 流式解析（`delta.content` + `delta.reasoning_content`），ESC 取消，虚拟线程 + stdin 轮询。

## 模块结构

| 模块 | 说明 |
|------|------|
| `gsim-lib` | 核心引擎 — Agent 系统、LLM 管理、WorldInfo、MCP 服务、HTTP API、Web UI、DocStore |
| `gsimap` | 六角格地图 — MapData 模型、地形生成、20 个 MCP 工具、Canvas 前端（:8711） |
| `gsim-app` | CLI 入口 — 单 `Main.java`，shaded fat JAR |

## HTTP API

| 方法 | 路径 | 说明 |
|------|------|------|
| GET | `/api/status` | 应用状态 |
| POST/GET | `/api/tasks` | 任务创建/列表 |
| GET | `/api/tasks/{id}/events` | SSE 任务事件流 |
| POST | `/api/tasks/{id}/cancel` | 取消任务 |
| POST | `/api/command` | 执行 CLI 命令 |
| POST | `/api/command/stream` | SSE 流式命令 |
| GET/POST | `/api/config` | 配置管理 |
| GET/POST | `/api/messages` | 消息管理 |
| GET/POST | `/api/players` | 玩家管理 |
| GET/POST | `/api/roots` | Root 管理 |
| GET/POST | `/api/pins` | 固定信息 |
| GET/POST | `/api/experiences` | 经验管理 |
| GET/POST | `/api/skills` | Skill 管理 |
| GET | `/api/tools` | 工具列表 |
| GET | `/api/help` | 帮助信息 |
| GET | `/api/where` | 当前位置 |
| POST | `/api/compact` | 缓存压缩 |
| POST | `/api/save` | 保存 |
| POST | `/api/import` | 资料导入 |
| GET | `/api/logs/[{taskId}]` | 日志 |
| GET | `/api/outputs/[{taskId}]` | 输出文件 |

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
