# GSimulator 架构文档

## 分层架构

```
┌──────────────────────────────────┐
│        Presentation Layer        │  CLI REPL / Future Web UI
├──────────────────────────────────┤
│      InteractionManager          │  命令解析、路由、结果格式化
├──────────────────────────────────┤
│      Application Services        │  Campaign, Turn, PlayerAction
├──────────────────────────────────┤
│         Agent Layer              │  Orchestrator + 专业 Agents
├──────────────────────────────────┤
│      Infrastructure Layer        │  LLM Client, ChromaDB, Storage
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

## Future Web UI Extension

WebInteractionAdapter 预留给未来 Web UI 使用。设计要点：

1. InteractionManager 的输出已是 `InteractionResult`，可直接转为 HTTP JSON 响应
2. 命令类不依赖终端，可被 Web 路由复用
3. ConsoleOutputFormatter 只用于 CLI，Web 使用 JSON
4. 会话状态可序列化，支持多会话管理

Web UI 只需：
- 实现 `WebInteractionAdapter`（HTTP Server 层）
- 实现会话持久化
- 前端通过 API consume `InteractionResult`
