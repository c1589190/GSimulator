# RUNTIME-FLOWS — 运行时关键流程

> 本文档描述 GSimulator 运行时序列与执行流程。详见
> [`ARCHITECTURE.md`](ARCHITECTURE.md)、[`DATA-MODEL.md`](DATA-MODEL.md)、[`TOOL-CONTRACTS.md`](TOOL-CONTRACTS.md)，
> 开发约定见根目录 `CLAUDE.md`。

---

## 1. 启动流程 (Startup Flow)

```
main() (gsim-app/Main.java)
  │
  ├── Phase 1: ConfigLoader
  │     ├── 解析 CLI args (--no-cli, --doctor, --help, --init-config)
  │     ├── 加载 gsim.properties (自动生成模板)
  │     ├── 首次运行 → ConfigWizard (交互式 LLM 配置)
  │     └── 产出 AppConfig
  │
  ├── Phase 2: Bootstrap + Assembly
  │     ├── WorldIndexManager 扫描 worlds/ → ActiveStateManager 恢复活跃节点
  │     ├── GSimulatorApplication 组装 ApplicationContext:
  │     │     ├── ToolRegistry (空), LlmManager (llms.json), EventBus
  │     │     ├── SessionPool, CachesManager, ApiManager (HTTP 路由)
  │     │     └── MapService (LRU 32), GsimapToolRegistrar (20 map tools)
  │     └── 注册 core tools: worldinfo (14), agent (11), doc (9), cache (4), llm (1), import (3)
  │
  └── Phase 3: Transport (blocking)
        ├── CLI 模式 (默认):
        │     ├── WebUiServer.start(:8710) — Thymeleaf + HTMX
        │     ├── GsimapHttpServer.start(:8711) — Canvas map editor
        │     ├── CliWebSocketServer.start(:8712) — real-time chat
        │     └── app.startCliRepl() — JLine REPL (阻塞主线程)
        │
        └── MCP 模式 (--no-cli):
              ├── System.out → System.err (保护 stdio)
              ├── StdioMcpTransport + GsimMcpServer.start() — JSON-RPC 2.0 loop (阻塞)
              └── 同样启动 :8710, :8711, :8712
```

---

## 2. Agent ToolLoop — 核心执行流程

```
用户输入 (CLI / HTTP / WebSocket) → ChatCommand.run()
  │ 加载历史 (CacheSession) → write-through cache 回调
  ▼
AbstractAgent ToolLoop (while round ≤ maxRounds):
  │
  ├── [1] 计算可用工具集合
  │     ToolGroupManager.computeAllowedTools()
  │     = DEFAULT_TOOLS ∪ {activated_groups 的成员}
  │     → ToolDef[] (含 JSON Schema)
  │
  ├── [2] LLM 调用
  │     LlmManager.chat() / submit() → StreamPool 轮询
  │     ├── System prompt (AgentConfig.staticSystemPrompt)
  │     ├── ToolDef[] + 消息历史 + 用户输入
  │     └── emit llmContentDelta (流式)
  │
  ├── [3] 解析 LLM 响应
  │     ├── API 原生 tool_calls → fromApiToolCalls()
  │     ├── 文本 JSON fallback → ToolCallExtractor.extractAllToolCalls()
  │     └── 纯文本 → 直接接受 (仅 requireFinishAction()=false)
  │
  ├── [4] 执行工具 (per ParsedToolCall)
  │     ├── ToolRoutePolicy.validateBeforeExecute()
  │     │    ├── 工具组激活检查 → 未激活返回错误
  │     │    └── 权限检查 → category + permission level
  │     ├── ToolPermissionGate.askConfirmation()
  │     │    ├── CLI: READ→允许, MUTATING→Y/A/N, DESTRUCTIVE→Y/N
  │     │    └── MCP: AutoApprove
  │     ├── ToolRegistry.call(ToolCall) → AgentTool.execute() → ToolResult
  │     ├── [TOOL_RESULT] 反馈文本追加到消息列表
  │     └── finish_action? → 验证 message → ChatResult
  │
  ├── [5] 无工具轮次: 无效意图→修正重发, 空内容→提示, 连续3轮→放弃
  │
  └── [6] 循环回 [1] (未 finish_action)
        │
        ▼
AgentResult { finalText, rounds, toolCallCount }
  → CLI / WebSocket / HTTP JSON
```

---

## 3. SubAgent 派发流程

```
OrchestratorAgent
  │  LLM 调用 dispatch_sub_agent(type="sim", prompt="...", agentConfig)
  ▼
AgentFactory.dispatch(type, prompt, taskId, sessionId, cacheId)
  ├── 加载 AgentConfig (data/agents/{type}.json / classpath)
  ├── 创建 AbstractAgent 子类, 设置 maxPermission + CacheSession (write-through)
  ├── Thread.ofVirtual().start(subAgent::run) → 返回 instanceId ("sim-3")
  ▼
SubAgent 独立 ToolLoop (只读, 16 轮上限, 120s 超时)
  │
  ▼
结果返回 → 同步阻塞 DispatchSubAgentTool 反馈
  │
OrchestratorAgent 继续 LLM 循环
  ├── 可选: collect_sub_agent_results → 收集全部运行中结果
  └── ESC 取消 → AgentFactory.cancelAll() → 传播到所有 SubAgent
```

---

## 4. MCP 协议流程

```
MCP Client (Claude Desktop / Cursor) — JSON-RPC 2.0 over stdin/stdout
  │
  ▼
StdioMcpTransport (readLine / writeLine → 原始 stdout)
  │
  ▼
AbstractMcpServer 事件循环:
  │
  ├── initialize → { protocolVersion: "2024-11-05", capabilities: {tools: {}} }
  ├── notifications/initialized → no-op
  │
  ├── tools/list → McpToolRegistry → ToolDef[]
  │     → gsim_ prefix (core) / gsimap_ prefix (map)
  │     → worldId + _page + _pageSize 自动注入 schema
  │
  └── tools/call → 提取 name+args → ToolRegistryMcpAdapter
        → ToolRegistry.call() → ToolResult → { content: [{type: "text", text: ...}] }
```

---

## 5. WorldInfo 写入流程

```
write_element(worldId, nodeId, checkpointId, key, value, mode="upsert")
  ▼
WorldInformation.upsertElement(nodeId, checkpointId, key, value)
  ├── 定位 Node (不存在→报错)
  ├── 定位或自动创建 Checkpoint (mode=upsert)
  ├── 定位或创建 Element (按 key 匹配)
  ├── mode=append → 追加值; mode=upsert → 覆盖
  ├── 更新 keywordIndex (倒排), tagIndex, links 引用
  ▼
NodeLoader.save(node) → data/worlds/{worldId}/nXXXX.json
  ▼
ActiveStateManager.persist() → data/worlds/{worldId}/state/active.json
```

---

## 6. Gsimap Diff-Chain 重建流程

```
Turn 0: MapStore.save(worldId, "n0000", mapData)
         → n0000_map.json (完整快照)

Turn 1: MapService.save(worldId, "n0001", modifiedMap)
         → MapDiff.compute(base=n0000, modified=n0001) → n0001_map.json (diff only)

Turn 2: MapService.save(worldId, "n0002", modifiedMap)
         → MapDiff.compute(base=n0001, modified=n0002) → n0002_map.json (diff only)

读取任意节点:
MapService.resolve(worldId, "n0002")
  → LRU cache miss → MapResolver.rebuild("n0002")
      ├── 读取 n0000_map.json (完整快照)
      ├── 按分支链顺序 apply(diff): n0001 → n0002
      └── 返回完整 MapData → 写入 LRU cache (max 32)
```

---

## 7. HTTP API 请求流程

```
HTTP Client → WebUiServer (:8710) → Javalin 路由 → ApiHandler
  ▼
Command (e.g. ChatCommand) → Agent ToolLoop (见 #2)
  ▼
AgentProgressSink 链 (实时扇出)
  ├── CliAgentProgressSink → 控制台进度条
  ├── EventBusAgentProgressSink → EventBus → SseEventSink → SSE 流
  └── SessionPoolBridge → SessionPool → CliWebSocketServer → 浏览器
```

---

## 8. Event 类型

| 事件 | Payload |
|------|---------|
| `command_started` / `command_done` / `command_error` | `{ taskId, sessionId, cmd/result/error }` |
| `tool_started` / `tool_done` / `tool_error` | `{ toolName, args/result/error }` |
| `llm_started` / `llm_delta` / `llm_reasoning_delta` / `llm_done` | `{ provider, model, content/reasoning/finishReason+usage }` |
| `result` / `done` | `{ finalText }` / `{ taskId }` |
| `log` / `run_stage` | `{ level, message }` / `{ stage }` |
| `import_progress` / `search_progress` | `{ imported, total }` / `{ query, resultsFound }` |

---

## 9. Cross-References

- [`ARCHITECTURE.md`](ARCHITECTURE.md) — 模块架构总览
- [`DATA-MODEL.md`](DATA-MODEL.md) — 流程流转的数据结构 (Node, Element, MapData, CacheSession...)
- [`TOOL-CONTRACTS.md`](TOOL-CONTRACTS.md) — ToolCall / ToolResult 契约及所有工具签名
- `CLAUDE.md` (项目根目录) — Agent 配置、ToolLoop 参数、开发规则
