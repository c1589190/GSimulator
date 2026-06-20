# GSimulator

基于 Java 21 + Maven 的多 Agent 推演工作流引擎，服务于**文游 / 架空历史 / 玩家行动推演**场景。

它不是一个聊天机器人，而是一个可审计、可回放、可扩展的回合制推演系统 —— LLM 通过工具调用操作知识库、分支节点、世界状态和推演内容，所有操作持久化并可回溯。

第一版提供 CLI REPL 模式 + HTTP API + SSE 流式事件输出。

## 快速开始

### 环境要求

- Java 21+
- Maven 3.8+

### 构建与运行

```bash
mvn package
java -jar target/GSimulator.jar               # 仅 CLI（默认）
java -jar target/GSimulator.jar --http        # 仅 HTTP API
java -jar target/GSimulator.jar --cli --http  # CLI + HTTP API
```

### 配置

```bash
cp .env.example .env
```

关键环境变量：

| 变量 | 说明 | 默认值 |
|------|------|--------|
| `LLM_BASE_URL` | OpenAI 兼容 API 端点 | — |
| `LLM_API_KEY` | API 密钥 | — |
| `LLM_MODEL` | 模型名称 | `deepseek-v4-pro` |
| `LLM_TEMPERATURE` | 温度参数 | `0.3` |
| `API_HOST` | HTTP 监听地址 | `127.0.0.1` |
| `API_PORT` | HTTP 监听端口 | `8710` |
| `API_ENABLED` | 启用 HTTP API | `false` |
| `CHROMA_BASE_URL` | ChromaDB REST API | — |
| `CHROMA_ENABLED` | 启用 ChromaDB | `false` |

## 核心特性

### 统一自然语言入口

使用 `/chat` 命令与 LLM 对话。LLM 通过 API tool_calls 自动选择合适的工具完成推演任务，无需用户记忆命令。

```
gsim> /chat 查一下龙门近卫局的资料
gsim> /chat 把这段设定保存到知识库
gsim> /chat 结算本回合并进入下一回合
```

### 工具组按需激活（v2）

68 个 Agent 工具按功能分为 10 组，LLM 通过 `activate_tool_groups` 按需激活：

| 工具组 | 激活 key | 说明 |
|--------|----------|------|
| 玩家行动 | `player_action` | 查看/记录/修订玩家行动 |
| 推演内容 | `simulation` | 读写推演正文/场景/事件 |
| 知识库 | `knowledge` | 语义搜索 + 关键词检索 + CRUD |
| 分支变更 | `branch_mutation` | 创建/切换/返回/推进 branch |
| 回合结算 | `settlement` | 保存和查询回合结算 |
| 导入文档 | `import_doc` | 浏览 import 目录下文档 |
| 玩家档案 | `player_profile` | 维护玩家长期资料 |
| 根节点管理 | `root_mgmt` | 读写 root 世界设定 |
| 分支记忆 | `branch_memory` | 搜索翻阅历史节点 |
| 文件搜索 | `search` | 全文搜索 Wiki 文件 |

### 知识库（embDB / KnowledgeStore）

- **语义搜索**（`knowledge_search`）：基于 embedding 向量相似度
- **关键词搜索**（`keyword_search`）：FTS5 + LIKE，无需 embedding profile
- **知识修改链**（KnowledgeChain）：按 targetKey + ancestor path 自动拼接完整知识演进
- **分支隔离**：查询结果按当前 branch 祖先路径过滤
- **物理隔离**：每个 root 独立 SQLite 数据库

### Branch 节点系统

- 树形分支结构，每个节点独立上下文
- `branch_next_turn` 原子操作（创建子节点 + 切换）
- 节点概览链（BaseContextSnapshot）自动注入上下文
- 分支记忆工具（搜索、日志过滤、固定内容）

### Root Workspace 治理

- 多 root 支持，每个 root 独立世界观/剧本
- root 文件（world.md / entities.md / rules.md / players.md / input.md）
- 根节点权限控制（不在根节点时禁止修改 root 文件）

### LLM 流式输出

- SSE 流式解析，支持 `delta.content` 和 `delta.reasoning_content`
- CLI 端 inline 打印 + 粗体高亮正式输出
- ESC 键取消当前 LLM 对话轮次
- 虚拟线程 + stdin 轮询

### CLI 进度与权限

- 工具执行进度可视化（等待 LLM → 执行工具 → 完成）
- 写入确认门禁（Y/A/N）—— MUTATING/DESTRUCTIVE 工具需确认
- 流式预览渲染

## CLI 命令

| 命令 | 说明 |
|------|------|
| `/chat <自然语言>` | 统一 Agent 入口（推荐） |
| `/status` | 当前 root / branch / 状态 |
| `/config` | 交互式配置向导 |
| `/compact` | 压缩对话历史（上下文窗口管理） |
| `/import` | 本地文件导入 |
| `/import <URL>` | 网页抓取导入 |
| `/searchdb` | 知识库搜索 |
| `/help` | 帮助 |
| `/exit` | 退出 |

## HTTP API

### 端点列表

| 方法 | 路径 | 说明 |
|------|------|------|
| GET | `/api/status` | 应用状态 |
| POST | `/api/tasks` | 创建任务（推荐） |
| GET | `/api/tasks` | 任务列表 |
| GET | `/api/tasks/{id}` | 任务状态 |
| GET | `/api/tasks/{id}/events` | SSE 任务事件流 |
| POST | `/api/tasks/{id}/cancel` | 取消任务 |
| POST | `/api/command` | 执行 CLI 命令（旧） |
| POST | `/api/command/stream` | SSE 流式命令（旧） |
| GET/POST | `/api/campaigns` | Campaign 列表/创建 |
| GET | `/api/campaigns/{id}` | Campaign 详情 |
| POST | `/api/campaigns/{id}/load` | 加载 Campaign |
| GET/POST | `/api/campaigns/{id}/turns` | Turn 列表/创建 |
| GET | `/api/campaigns/{id}/turns/{turnId}` | Turn 详情 |
| POST | `/api/campaigns/{id}/turns/{turnId}/activate` | 激活 Turn |
| GET/POST/DELETE | `/api/campaigns/{id}/turns/{turnId}/actions` | PlayerAction CRUD |
| POST | `/api/import/local` | 本地导入 |
| POST | `/api/import/url` | URL 导入 |
| GET/POST | `/api/searchdb` | 搜索知识库 |
| GET | `/api/logs[/{taskId}]` | 日志列表/详情 |
| GET | `/api/outputs[/{taskId}]` | 输出文件 |
| GET/POST | `/api/branches` | 分支管理 |

### SSE 事件格式

```
event: {type}
data: {"sessionId":"...","taskId":"...","type":"...","..."}
```

事件类型：`command_started`, `command_done`, `command_error`, `log`, `run_stage`,
`import_progress`, `search_progress`, `tool_started`, `tool_done`, `tool_error`,
`llm_started`, `llm_delta`, `llm_reasoning_delta`, `llm_done`, `result`, `done`

### 推荐用法

```bash
# 创建任务
curl -X POST http://127.0.0.1:8710/api/tasks \
  -H "Content-Type: application/json" \
  -d '{"sessionId":"default","command":"/status"}'

# 订阅 SSE 事件流
curl -N http://127.0.0.1:8710/api/tasks/{taskId}/events
```

## 项目结构

```
src/main/java/com/gsim/
├── app/                   — 应用启动、上下文、配置
├── api/                   — HTTP API 层（路由、SSE、handler）
│   ├── handlers/          — API handler 实现
│   └── dto/               — 请求/响应 DTO
├── event/                 — 统一事件系统（EventBus、SSE/Console sink）
├── interaction/           — 交互层（CLI REPL、命令解析、格式化）
│   └── commands/          — 命令实现
├── campaign/              — 战役/回合/玩家行动 CRUD
├── branch/                — Branch 节点管理
│   └── tool/              — Branch 相关 Agent 工具
├── agent/                 — Orchestrator 和各专业 Agent
│   └── tool/              — Agent 工具（finish_action、activate_tool_groups）
├── knowledge/             — 知识库核心
│   ├── store/             — SQLiteKnowledgeStore
│   ├── search/            — KnowledgeSearchService（语义/关键词）
│   ├── embed/             — Embedding 模型和 profile 管理
│   ├── tool/              — 8 个知识库 Agent 工具
│   └── chunk/             — 文本分块
├── chroma/                — ChromaDB REST 客户端
├── llm/                   — LLM 统一封装（Provider、SSE、流式、ToolDef）
├── prompt/                — Prompt 模板管理
├── compact/               — 上下文压缩
├── crawler/               — 联网搜索和网页抓取
├── importdata/            — 资料导入管道
├── webimport/             — 网页抓取管道
├── config/                — 配置加载、环境映射、Doctor、Wizard
├── chat/                  — Chat 服务
├── resource/              — 资源文件读取
├── context/               — 上下文会话管理
├── data/                  — DataManager（数据根管理）
├── root/                  — Root Workspace 治理
├── storage/               — 持久化存储
├── output/                — 输出格式化（Markdown / JSON）
└── util/                  — 工具类（ID 生成、时间、JSON）
```

## 架构原则

1. **交互层抽象** — 所有 IO 通过 `InteractionManager`，禁止业务代码直接读写控制台
2. **LLM 统一封装** — 所有 LLM 调用通过 `LlmManager`，禁止业务代码拼 HTTP 请求
3. **ChromaDB 统一封装** — 所有数据库操作通过 `ChromaClient` 接口
4. **Prompt 外置** — 所有 prompt 放在 `src/main/resources/gsim/prompts/`
5. **配置集中** — 环境变量统一走 `AppConfig`
6. **离线测试** — 使用 `FakeLlmManager` / `FakeChromaClient`，不依赖外部服务
7. **DTO 优先 record** — 不可变数据优先使用 Java record

## 开发

### 运行测试

```bash
mvn test    # 1314 tests
```

### 开发阶段

| Phase | 功能 | 状态 |
|-------|------|------|
| Phase 0 | 项目审计与计划 | ✅ |
| Phase 1 | 基础项目骨架 | ✅ |
| Phase 2 | 交互层 REPL | ✅ |
| Phase 3 | Campaign / Turn / PlayerAction | ✅ |
| Phase 4 | PromptManager 与 LLM JSON 系统 | ✅ |
| Phase 5 | ChromaDB 与 /searchdb | ✅ |
| Phase 6 | /import 本地导入 + URL 网页导入 | ✅ |
| Phase 7 | Orchestrator 和 Agent 工作流 | ✅ |
| Phase 8 | 玩家行动分析、时间线、世界状态 | ✅ |
| Phase 9 | ResearchAgent | 🔧 |
| Phase 10 | WriterAgent 和最终出文 | 🔧 |
| v2 | ToolLoop 重构 + 工具组系统 + 流式 | ✅ |
| — | Compact 上下文压缩子系统 | ✅ |

## 许可证

内部项目
