# 上下文加载可解释 + Agent 任务简报 + finish_action 卡住原因可见

## 动机

实测一轮简单查询加载 46028 chars，用户无法理解：
1. 为什么简单查询加载这么多字符
2. activeBranch=b0002 时到底加载了什么上下文
3. LLM 当前在哪个阶段（选工具/等结果/卡 finish_action）
4. LLM 想结束但没合法 finish_action 时没有可见提示

## 约束

- 不改 DB、import、WebUI、业务工具逻辑
- 不改 ToolCallExtractor 三层协议、finish_action 验证、branch_next_turn
- 不做上下文瘦身、不做工具 schema 子集选择
- AgentProgressSink 只做 side-channel CLI 输出，绝不写入 BranchMessageStore 或 LLM messages
- TaskBrief 只做 debug/progress，不改变工具选择和上下文构造策略

## 新增类型

### 1. AgentProgressSink（接口）
```java
interface AgentProgressSink {
    void onProgress(AgentProgressEvent event);
}
```
- `CliAgentProgressSink` → CLI 默认，打印 `[Agent] ...`
- `NoopAgentProgressSink` → 测试/非 CLI 默认
- 构造注入 OrchestratorAgent，null 安全 fallback Noop

### 2. AgentProgressEvent（record）
```java
record AgentProgressEvent(
    String phase,           // CONTEXT_LOADED | WAITING_LLM | TOOL_SELECTED |
                            // TOOL_EXECUTING | TOOL_SUCCESS | TOOL_FAILED |
                            // AWAITING_FINISH_ACTION | PLAIN_ANSWER_WITHOUT_FINISH |
                            // INVALID_BRACKET_INTENT | FINISH_ACTION_REJECTED
    int round, int maxRounds,
    String detail,          // 简短人读
    Map<String, String> meta // 结构化补充
)
```

### 3. AgentContextMeta（record）
由 NodeAgentChatService 结构化构造，传入 OrchestratorAgent，禁止从 markdown 反向解析：
```java
record AgentContextMeta(
    String activeRoot,              // "root.arknights-terra-1096"
    String activeBranch,            // "branch.b0002"
    String contextMode,             // "FULL_CONTEXT" | "BRANCH_CONTEXT" | ...
    boolean fullWorldContextLoaded, // true（当前默认行为）
    String contextModeReason,       // "current_context_builder_default"
    List<String> branchPath,        // [branch.b0001, branch.b0002]
    List<String> loadedParentBranches,
    boolean currentBranchLoaded
)
```

## 修改的文件

### `OrchestratorAgent.java`（主修改）

1. **构造函数**增加 `AgentProgressSink` 参数（null 安全）
2. **`chatWithContextSession` / `runWithContextSession`** 增加 `AgentContextMeta` 参数
3. **`runToolLoop` / `runSimToolLoop`**：每一轮调用前
   - `logContextLoad()` — 拆分 messages + tools schema 字符数
   - `logTaskBrief()` — 当前轮次意图分析
   - 每个阶段发射 `AgentProgressEvent`
   - `detectFinishIntent()` — 识别 3 种「想结束但没合法结束」情况

### `ToolLoopDebug.java`（扩展）

新增方法：

- **`logContextLoad(log, loopName, round, messages, toolDefs, contextMeta)`**
  输出单独的 `TOOL_LOOP CONTEXT_LOAD` 段，粗粒度拆分：
  ```
  systemPrompt chars=...
  rootContext chars=...
  branchContext chars=...
  messageHistory chars=...
  toolSchemas chars=...
  userInput chars=...
  ---
  messageChars=<sum of all message content>
  toolsJsonChars=<estimated JSON size of tools[]>
  estimatedRequestChars=<messageChars + toolsJsonChars>
  ```
  toolsJsonChars 估算：用 Jackson 序列化 `tools[]` 到临时字符串再计长。

- **`logTaskBrief(log, loopName, round, userText, lastToolResult, expectedTools)`**
  输出 `TOOL_LOOP TASK_BRIEF` 段，含 userIntent / expectedNextStep / expectedTools

- **`detectFinishIntent(content, apiToolCallCount, textFallbackCount, cleanedDraft, invalidToolIntent)`**
  返回 `FinishIntent` enum：NONE / PLAIN_ANSWER / INVALID_BRACKET / FINISH_REJECTED

### `GSimulatorApplication.java`
CLI 模式创建 `CliAgentProgressSink(System.out)` 注入 OrchestratorAgent。

### `NodeAgentChatService.java`
调用 orchestrator 前构造 `AgentContextMeta`，从 DataManager 取分支路径、root 名等。

## 测试策略

8 个新测试，全部使用 FakeLlmClient + NoopAgentProgressSink + 自建 fake Sink 检查事件。

| # | 测试 | 关键断言 |
|---|------|---------|
| 1 | `ContextLoadDebugShowsPartsTest` | fake FakeLlmClient calls → captured system prompt contains orchestrator-system.md, ToolCatalog, context; debug log shows part breakdown; toolsJsonChars > 0 |
| 2 | `TaskBriefShowsExpectedToolForQueryTest` | userText="确认行动记录" → task brief 含 PLAYER_ACTION_QUERY 语义提示 |
| 3 | `TaskBriefExpectsFinishActionAfterToolTest` | 工具执行后下一轮 → expectedNextStep=FINISH_ACTION |
| 4 | `AgentProgressSinkReceivesRoundEventsTest` | 完整 chat 流程 → fake sink 收到 CONTEXT_LOADED, WAITING_LLM, TOOL_SELECTED, TOOL_SUCCESS, AWAITING_FINISH_ACTION |
| 5 | `CliAgentProgressSinkPrintsShortStatusTest` | CliAgentProgressSink.format(…) 输出 <= 120 chars，不含敏感内容 |
| 6 | `AgentProgressSinkShowsFinishActionRejectedReasonTest` | finish_action 含 [工具结果] → progress event = FINISH_ACTION_REJECTED，detail 含 reject 原因 |
| 7 | `ToolLoopDetectsPlainAnswerWithoutFinishActionTest` | cleanedDraft 非空 + 无 tool + 非 invalid → PLAIN_ANSWER_WITHOUT_FINISH |
| 8 | `ToolLoopDetectsInvalidBracketIntentProgressTest` | bracket invoke → INVALID_BRACKET_INTENT progress event |

## 禁止

- AgentProgressSink 写入 BranchMessageStore
- AgentProgressEvent 进入 LLM messages
- 从 baseContextMarkdown 反解 activeRoot/activeBranch
- 修改上下文构造策略或工具 schema 子集
- TaskBrief 影响工具选择逻辑
- plain_answer 检测做复杂语义判断
