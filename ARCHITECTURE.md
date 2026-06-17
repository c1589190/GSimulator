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

如果 ChromaDB 不可用，文件保存到 `data/pending-imports/`。

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
