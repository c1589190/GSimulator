# GSimulator 架构文档

## 分层架构

```
┌──────────────────────────────────┐
│        Presentation Layer        │  CLI REPL / HTTP API / Future Web UI
├──────────────────────────────────┤
│      InteractionManager          │  命令解析、路由、结果格式化
│                                  │  (CLI 和 HTTP 共用)
├──────────────────────────────────┤
│         EventBus                 │  GSimEvent 发布/订阅
│         ├── ConsoleEventSink     │  CLI 流式显示
│         └── SseEventSink         │  HTTP SSE 流式输出
├──────────────────────────────────┤
│      Application Services        │  Campaign, Turn, PlayerAction
├──────────────────────────────────┤
│         Agent Layer              │  Orchestrator + 专业 Agents
├──────────────────────────────────┤
│      Infrastructure Layer        │  LLM Client (含流式), ChromaDB, Storage
└──────────────────────────────────┘
```

## InteractionManager 设计

```
┌──────────┐     ┌─────────────────────┐
│   Main   │────>│ InteractionManager   │
└──────────┘     │                      │
                 │  parseCommand()      │
                 │  executeCommand()    │
                 │  formatResult()      │
                 └──────┬──────────────┘
                        │
           ┌────────────┼────────────┐
           │            │            │
    ┌──────▼──┐  ┌─────▼────┐ ┌────▼─────┐
    │  CLI    │  │   Web    │ │ Commands │
    │ Adapter │  │ Adapter  │ │ (shared) │
    └─────────┘  └──────────┘ └──────────┘
```

- `InteractionManager` 不直接读写控制台
- `ConsoleInteractionAdapter` 处理终端 I/O
- `WebInteractionAdapter` 预留给未来 Web UI
- 所有命令类实现统一接口，可复用

## Agent Workflow (/run)

```
/run 触发
  │
  ▼
OrchestratorAgent ──> TaskPlan
  │
  ├──> KnowledgeRouterAgent ──> RetrievalPlan ──> ChromaDB ──> EvidenceBundle
  │
  ├──> ResearchAgent (optional) ──> SearchPlan ──> WebSearch ──> ResearchDocuments
  │
  ├──> PlayerActionAnalyzerAgent ──> PlayerActionAnalysis[]
  │
  ├──> TimelineAgent ──> TimelineEvent[]
  │
  ├──> WorldStateService ──> StateChange[]
  │
  ├──> WriterAgent ──> WriterOutput (public + private + citations)
  │
  └──> TaskLogService ──> JSON TaskLog
       MarkdownOutputWriter ──> .md 文件
       ConsoleOutputFormatter ──> CLI 显示
```

## ChromaDB Routing

```
LLM 判断需要知识检索
  │
  ▼
KnowledgeRouterAgent
  │
  ├── 分析 TaskContext
  ├── 生成 RetrievalPlan
  │     ├── RetrievalQuery 1: world_lore
  │     ├── RetrievalQuery 2: timeline_events
  │     └── RetrievalQuery 3: characters
  │
  ▼
Java ChromaClient 执行查询
  │
  ▼
EvidenceBundle 返回
  │
  ▼
后续 Agent 只能使用 EvidenceBundle 中的数据
```

LLM 不能直接操作 ChromaDB，必须通过 RetrievalPlan → Java → ChromaDB 的间接路径。

## Import Pipeline

### 本地导入

```
/import 触发
  │
  ▼
ImportFileScanner ──> 扫描 import/ 目录
  │
  ▼
ImportClassifier ──> 判断目标 collection
  │
  ▼
TextChunker ──> 分段
  │
  ▼
ChromaClient ──> 写入 ChromaDB
  │
  ├── 成功 ──> 移动到 import/done/
  └── 失败 ──> 移动到 import/failed/
```

### URL 导入

网页采集和入库解耦，爬虫不直接写知识库：

```
/import <URL> 触发
  │
  ▼
ImportCommand ──> 解析 URL 和参数
  │
  ▼
WebImportManager ──> 调度网页采集
  │
  ├── MediaWikiSiteDetector ──> 检测站点类型
  │
  ├── MediaWiki 站点:
  │   └── MediaWikiCrawler ──> MediaWikiApiClient ──> 获取 parsed HTML
  │
  └── 普通站点:
      └── GenericWebsiteCrawler ──> JsoupWebPageFetcher ──> OkHttp 请求
              └── HtmlTextExtractor ──> Jsoup 提取正文
                    └── WebImportFileWriter ──> 写入 import/web/{host}/{title}-{hash}.txt
  │
  ▼
ImportManager.importSpecificFiles() ──> 入库
```

> ⚠️ **当前状态**：ImportManager 入库仍是 **stub**。
> 网页抓取管道（URL → txt）已完整可用，但 `importSpecificFiles()` 和 `doImport()`
> 目前返回占位结果，文件暂存到 `data/pending-imports/`。
> 下一阶段将实现 LocalKnowledgeStore 替换 stub，完成真正的知识库写入。

### 网页抓取参数

| 参数 | 默认值 | 说明 |
|------|--------|------|
| --max-pages | 50 | 最多抓取页数 |
| --depth | 2 | 递归深度 |
| --delay-ms | 1000 | 请求间隔 |
| --fetch-only | false | 只生成 txt 不入库 |
| --no-crawl | false | 只抓当前页 |
| sameHostOnly | true | 只抓同域名链接 |
| timeoutSeconds | 15 | 请求超时 |
| maxBytesPerPage | 5MB | 单页最大字节 |
| userAgent | GSimulatorBot/0.1 | 请求 UA |

### JS 渲染

本阶段暂不支持 Playwright/Selenium。`RenderedPageFetcher` 接口已定义，
默认 `DisabledRenderedPageFetcher` 返回警告：当前未启用 JS 渲染。

### 输出文件格式

```
import/web/{host}/{safe-title}-{hash}.txt

# {title}
Source URL: {url}
Fetched At: {time}
Site: {host}
Crawler: {crawlerName}
Collection Hint: world_lore
Tags: web,{host}
---
{cleanedText}
```

### 测试策略

- 自动化测试不访问真实外网，使用 MockWebServer、fixture HTML、FakeChromaClient
- mvn test 必须离线可跑
- 手动验收可小规模访问真实站点

## HTTP API 架构

HTTP API 使用 JDK 内置 `com.sun.net.httpserver.HttpServer`，不引入 Spring Boot。

### 组件

```
ApiManager ──> HttpServer
  └── ApiRouter ──> 注册所有路由
        ├── StatusApiHandler        GET  /api/status
        ├── CommandApiHandler       POST /api/command
        ├── StreamCommandHandler    POST /api/command/stream (SSE)
        ├── CampaignsApiHandler     /api/campaigns/**
        ├── ImportApiHandler        /api/import/**
        ├── SearchDbApiHandler      /api/searchdb
        ├── LogsOutputsApiHandler   /api/logs/**, /api/outputs/**
        └── BranchesApiHandler      /api/branches/** (预留)
```

### 事件系统

CLI 和 HTTP 共用 EventBus：
- `EventBus` 发布 `GSimEvent`，先通过 `accepts()` 过滤再 `accept()`
- `EventSink` 接口新增 `accepts(GSimEvent)` 方法，默认接受所有事件
- `ConsoleEventSink` 订阅 EventBus（CLI 模式）
- `FilteredEventSink` 按 sessionId/taskId 过滤订阅（每条 SSE 连接）
- CLI 和 HTTP 必须复用 `InteractionManager` 和服务层

### Session 管理

- `SessionManager` 管理 `sessionId → InteractionSession` 映射
- API 请求不再全部共享同一个 session
- 每个 session 拥有独立的 `InteractionContext`，共享底层 services

### Task 管理

- `TaskManager` 创建和管理长任务生命周期（PENDING → RUNNING → DONE/FAILED/CANCELLED）
- 任务在虚拟线程中执行，通过 EventBus 发布事件
- `POST /api/tasks` 创建任务，`GET /api/tasks/{id}/events` 提供 taskId 级别 SSE 订阅

### SSE 流式事件

```
event: command_started
data: {"sessionId":"...","command":"...","taskId":"..."}

event: run_stage
data: {"stage":"analyze_actions","message":"正在分析玩家行动"}

event: llm_delta
data: {"text":"..."}

event: llm_reasoning_delta
data: {"text":"..."}

event: result
data: {"outputFile":"...","displayText":"..."}

event: done
data: {}
```

### LLM 流式接口

- `LlmClient.stream(LlmRequest, LlmStreamListener)` — 流式聊天
- 默认降级到非流式 `chat()`
- `delta.content` → `llm_delta` 事件
- `delta.reasoning_content` → `llm_reasoning_delta` 事件
- 不伪造 reasoning — 只有上游返回字段时才转发

## Future Web UI Extension

HTTP API 已就绪。Web UI 只需：
- 前端通过 API consume JSON 和 SSE 流
- 实现 Web UI 渲染
- 会话持久化（可选）
