# GSimulator — Claude 开发指南

## 项目简介

GSimulator 是一个基于 Java 21 + Maven 的多 Agent 推演工作流引擎，服务于"文游 / 架空历史 / 玩家行动推演"场景。它不是普通聊天机器人，而是一个可审计、可回放、可扩展的回合制推演系统。

第一版为 CLI REPL 模式，支持 HTTP API + SSE 流式事件输出，后续将扩展 Web UI。

## 架构原则

1. **交互层抽象** — 所有输入输出必须通过 `InteractionManager`，不允许业务代码直接读写控制台
2. **LLM 统一封装** — 所有 LLM 调用必须通过 `LlmClient` 接口，不允许业务代码直接拼 HTTP 请求
3. **ChromaDB 统一封装** — 所有数据库操作必须通过 `ChromaClient` 接口
4. **Prompt 外置** — 所有 prompt 放在 `src/main/resources/prompts/`，不在 Java 代码中写死复杂 prompt
5. **配置集中** — 所有环境变量读取统一走 `AppConfig`
6. **离线测试** — 所有测试使用 FakeLlmClient / FakeChromaClient，不依赖外部服务
7. **DTO 优先 record** — 不可变数据优先使用 Java record
8. **每阶段可运行** — 每完成一个 Phase，项目必须可编译、可测试、可运行

## Package 说明

```
com.gsim
├── app/            — 应用启动、上下文、配置
├── api/            — HTTP API 层（ApiManager、路由、SSE）
│   ├── handlers/   — API handler（status、command、campaigns 等）
│   └── dto/        — API 请求/响应 DTO
├── event/          — 统一事件系统（EventBus、EventSink、SSE/Console）
├── interaction/    — 交互层（CLI REPL、命令解析、结果格式化）
├── campaign/       — 战役/回合/玩家行动 CRUD
├── agent/          — Orchestrator 和各专业 Agent
├── chroma/         — ChromaDB REST 客户端和知识路由
├── llm/            — LLM 客户端统一封装（含流式接口）
├── prompt/         — Prompt 模板管理和渲染
├── crawler/        — 联网搜索和网页抓取
├── importdata/     — 资料导入管道
├── webimport/      — 网页抓取管道（URL → txt → import）
├── task/           — 任务上下文、计划、日志
├── timeline/       — 时间线事件管理
├── world/          — 世界状态、派系、角色
├── storage/        — 持久化存储（JSON 文件 / SQLite）
├── output/         — 输出格式化（Markdown / JSON / Console）
└── util/           — 工具类（ID 生成、时间、JSON）
```

## 运行命令

```bash
# 构建
mvn package

# 运行
java -jar target/GSimulator.jar

# 测试
mvn test
```

## LLM API 配置

通过环境变量设置：
- `LLM_BASE_URL` — OpenAI-compatible API 端点
- `LLM_API_KEY` — API 密钥
- `LLM_MODEL` — 模型名称（默认 deepseek-v4-pro）
- `LLM_TEMPERATURE` — 温度参数（默认 0.3）

## HTTP API 配置

通过环境变量设置：
- `API_HOST` — 监听地址（默认 127.0.0.1）
- `API_PORT` — 监听端口（默认 8710）
- `API_ENABLED` — 是否启用（默认 false）

启动方式：
```bash
java -jar target/GSimulator.jar               # 仅 CLI（默认）
java -jar target/GSimulator.jar --http        # 仅 HTTP API
java -jar target/GSimulator.jar --cli --http  # CLI + HTTP API
```

## HTTP API 列表

| 方法 | 路径 | 说明 |
|------|------|------|
| GET | /api/status | 应用状态 |
| POST | /api/tasks | 创建任务（推荐） |
| GET | /api/tasks | 任务列表 |
| GET | /api/tasks/{id} | 任务状态 |
| GET | /api/tasks/{id}/events | SSE 任务事件流 |
| POST | /api/tasks/{id}/cancel | 取消任务 |
| POST | /api/command | 执行 CLI 命令（旧） |
| POST | /api/command/stream | SSE 流式命令（旧） |
| GET/POST | /api/campaigns | Campaign 列表/创建 |
| GET | /api/campaigns/{id} | Campaign 详情 |
| POST | /api/campaigns/{id}/load | 加载 Campaign |
| GET/POST | /api/campaigns/{id}/turns | Turn 列表/创建 |
| GET | /api/campaigns/{id}/turns/{turnId} | Turn 详情 |
| POST | /api/campaigns/{id}/turns/{turnId}/activate | 激活 Turn |
| GET/POST/DELETE | /api/campaigns/{id}/turns/{turnId}/actions | PlayerAction CRUD |
| POST | /api/import/local | 本地导入 |
| POST | /api/import/url | URL 导入 |
| GET/POST | /api/searchdb | 搜索知识库（预留） |
| GET | /api/logs[/{taskId}] | 日志列表/详情 |
| GET | /api/outputs[/{taskId}] | 输出文件列表/详情 |
| GET/POST | /api/branches | 分支管理（预留） |

## SSE 流式事件格式

```
event: {type}
data: {"sessionId":"...","taskId":"...","type":"...","..."}

```

支持的事件类型：command_started, command_done, command_error, log, run_stage,
import_progress, search_progress, tool_started, tool_done, tool_error,
llm_started, llm_delta, llm_reasoning_delta, llm_done, result, done

### 事件过滤

- GSimEvent 包含 sessionId、taskId、type、time、data
- EventSink 通过 `accepts(GSimEvent)` 实现过滤（默认接受所有事件）
- SseEventSink / FilteredEventSink 可按 sessionId + taskId 过滤
- SSE 订阅某个 taskId 时只收到该 task 的事件
- 无 taskId 的事件广播给 sessionId 匹配的 sink

### 推荐用法

```bash
# 创建任务
curl -X POST http://127.0.0.1:8710/api/tasks \
  -H "Content-Type: application/json" \
  -d '{"sessionId":"default","command":"/status"}'

# 订阅任务 SSE 事件流（GET，适合浏览器 EventSource）
curl -N http://127.0.0.1:8710/api/tasks/{taskId}/events
```

## LLM 流式接口

LlmClient 新增 `stream()` 方法：
- 上游返回 `delta.content` → llm_delta 事件
- 上游返回 `delta.reasoning_content` → llm_reasoning_delta 事件
- 不伪造 reasoning — 只有上游确实返回 reasoning_content 时才转发
- 默认降级到非流式 chat() 实现

## 事件系统

CLI 和 HTTP 共用 EventBus：
- EventBus 发布 GSimEvent，先通过 `accepts()` 过滤再 `accept()`
- ConsoleEventSink 订阅 EventBus（CLI 模式）
- FilteredEventSink 按需订阅 EventBus（SSE 连接，按 sessionId/taskId 过滤）
- CLI 和 HTTP 共用 InteractionManager 业务逻辑

### Session 管理

- SessionManager 管理 `sessionId → InteractionSession` 映射
- API 请求不再全部共享同一个 session
- 每个 session 拥有独立的 InteractionContext，共享底层 services

### Task 管理

- TaskManager 创建和管理长任务（PENDING → RUNNING → DONE/FAILED/CANCELLED）
- 任务在虚拟线程中执行，通过 EventBus 发布事件
- 支持任务查询、列表、取消
- GET /api/tasks/{taskId}/events 提供 taskId 级别 SSE 订阅

## ChromaDB 配置

通过环境变量设置：
- `CHROMA_BASE_URL` — ChromaDB REST API 地址
- `CHROMA_ENABLED` — 是否启用（默认 false）

## Prompt 管理规则

- 所有 prompt 放在 `src/main/resources/prompts/`
- 每个文件必须包含 name, version, purpose, input variables, output format
- Prompt 必须要求模型输出结构化 JSON 或 Markdown
- 必须要求模型区分 facts / inferences / hypotheses

## InteractionManager 规则

- `Main` 只能做初始化，不能写业务逻辑
- 命令类只能调用应用服务，不能直接写复杂推演逻辑
- `InteractionManager` 负责协调命令解析和服务调用

## Agent 工作流规则

1. Orchestrator 生成 TaskPlan
2. KnowledgeRouter 生成 RetrievalPlan → ChromaDB 查询 → 返回 EvidenceBundle
3. 如需联网，ResearchAgent 生成 SearchPlan → 搜索/抓取 → ResearchDocument
4. PlayerActionAnalyzer 分析玩家行动
5. TimelineAgent 生成时间线事件
6. WorldStateService 生成状态变更
7. WriterAgent 生成最终输出
8. TaskLogService 保存完整日志

## DTO 设计规则

- 优先使用 Java record
- 所有 DTO 必须可 JSON 序列化
- 使用 `@JsonProperty` 保持字段名一致
- ID 使用 `IdGenerator` 生成，不要手动拼接

## 禁止事项

- ❌ 业务代码直接访问环境变量（走 AppConfig）
- ❌ 业务代码直接拼 HTTP 请求（走 LlmClient / ChromaClient）
- ❌ Main 中写业务逻辑
- ❌ 命令类中写复杂推演逻辑
- ❌ Prompt 写死在 Java 代码中
- ❌ 吞异常
- ❌ 输出 API Key
- ❌ 测试依赖外部服务
- ❌ 静态全局可变状态

## 开发阶段

- **Phase 0**: 项目审计与计划 ✅
- **Phase 1**: 基础项目骨架 ✅
- **Phase 2**: 交互层 REPL ✅
- **Phase 3**: Campaign / Turn / PlayerAction (下一阶段)
- **Phase 4**: PromptManager 与 LLM JSON 系统
- **Phase 5**: ChromaDB 与 /searchdb
- **Phase 6**: /import ✅ (本地导入 + URL 网页导入)
- **Phase 7**: Orchestrator 和 /run 骨架
- **Phase 8**: 玩家行动分析、时间线、世界状态
- **Phase 9**: ResearchAgent
- **Phase 10**: WriterAgent 和最终出文

## 开发提醒

- **提交前必须确认**: 手动验收后会产生测试残留文件（data/worlds/default/input.md、data/worlds/default/branches/b0001-contact.md 等），提交前务必 `git checkout` 恢复或 `rm` 清理，只保留初始化模板文件。
- **分支**: 当前在 `phase-web-import` 分支开发。提交命令: `git push origin phase-web-import`。
- **运行前删除 data/**: 首次或测试启动前 `rm -rf data/` 以验证自动初始化。
