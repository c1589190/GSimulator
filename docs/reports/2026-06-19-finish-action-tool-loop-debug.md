# P0 Follow-up: ToolLoop Debug 可观察性 + 连续 No-Tool 收束 + PlayerAction 查询提示

**Commit ref**: `phase-root-workspace-governance` / 前一个 commit `76bbd1f`
**Scope**: 仅 ToolLoop 控制流 + Debug 日志，不改 DB / import / WebUI / HTTP API / 业务工具

---

## 1. Problem

在 `finish_action` 架构收口 (`76bbd1f`) 之后，简单查询（如"第二回合有没有行动记录"）仍触发 `Agent did not call finish_action within 5 rounds`：

1. LLM 输出纯自然语言（无 tool call）→ ToolLoop 无工具轮烧满 5 轮 MAX_TOOL_ROUNDS 才报错
2. 无工具提醒太弱："你还没有调用 finish_action"，缺少 player_action_list 引导
3. ToolLoop 黑盒——出问题时只能看 finalText，无法快速定位是 LLM 输出异常、解析失败、还是验证拒绝
4. prompt 里没有规定"问行动记录 → 查 player_action_list → finish_action"的强制路径

## 2. Solution

### 2a. 连续无工具提前中止（OrchestratorAgent.java）

新增 `consecutiveNoToolRounds` 计数器，**连续 2 轮无工具调用立即返回错误**，不再烧满 5 轮：

```
while (toolRound < MAX_TOOL_ROUNDS):
    if tools found:
        consecutiveNoToolRounds = 0  // 重置
        ...
    else:
        consecutiveNoToolRounds++
        if consecutiveNoToolRounds >= 2 → return error  // 提前中止
        else → append reminder, continue
```

旧行为：纯文本 × 5 → 最多 5 次 LLM 调用 → 报错
新行为：纯文本 × 2 → 最多 2 次 LLM 调用 → 报错（节省 60% token）

### 2b. ToolLoopDebug.java（新增 286 行）

Package-private 静态 helper，所有方法 `if (!log.isDebugEnabled()) return;` 守卫，生产环境零开销。

8 个日志段覆盖整个 ToolLoop 生命周期：

| 段名 | 方法 | 输出字段 |
|------|------|----------|
| LLM_RESPONSE | `logLlmResponse` | rawChars, rawPreview (截断 2000) |
| CLEANED_DRAFT | `logCleanedDraft` | cleanedChars, cleanedPreview (截断 1500) |
| TOOL_EXTRACTION | `logToolExtraction` | toolCallCount, tools, containsFinishAction, suspectToolSyntax |
| TOOL_CALL | `logToolCall` | tool, argsPreview (截断 1000) |
| TOOL_RESULT | `logToolResult` | tool, success, errorCode, message, resultPreview |
| FINISH_ACTION | `logFinishAction` | status, messageChars, messagePreview |
| FINISH_ACTION VALIDATION | `logFinishAccepted` | finishAccepted, rejectReason, claim, requiredTool, successTools |
| NO_TOOL_ROUND | `logNoToolRound` | consecutiveNoToolRounds, action |
| NO_TOOL_ABORT | `logNoToolAbort` | reason, consecutiveNoToolRounds |
| FINAL_TEXT | `logFinalText` | source, chars, preview |

外加两个纯函数：

```java
static boolean isPlayerActionQuery(String userInput)  // 5 类关键词匹配
static String buildNoToolReminder(String userInput)    // 4 条基础规则 + 条件化 player_action_list 提示
static String noToolAbortError(int consecutive)        // 错误消息模板
```

### 2c. 增强无工具提醒

**旧提醒**：
> 你还没有调用 finish_action。请继续调用必要工具，或调用 finish_action 给出最终回复。

**新提醒**（当用户问"有没有玩家行动记录"时）：
> 你没有调用任何工具，也没有调用 finish_action。
>
> 规则：
> 1. 如果任务需要查询/写入/保存/切换，请先调用对应业务工具。
> 2. 如果任务已经可以直接回答，请调用 finish_action，并把最终回复放入 message。
> 3. 不要直接用普通自然语言结束。
> 4. 不要输出 [工具调用已执行]、[工具结果] 或 raw JSON。
>
> 用户正在询问玩家行动记录。请调用 player_action_list 查询当前 activeBranch，然后调用 finish_action 返回结果。

关键词匹配：`玩家行动` / `行动记录` / `当前回合行动` / `有没有行动` / `player action`

### 2d. Prompt 更新（orchestrator-system.md）

在「玩家行动记录规则」节新增 **player_action_list 查询规则（强制）**：

> 当用户询问以下自然语言时，必须调用 player_action_list：
> - "有没有玩家行动记录" / "当前回合行动" / "列出玩家行动" / "确认某节点有没有行动" / "第二回合有没有行动记录" / "当前 branch 有哪些玩家行动"
>
> 规则：
> 1. 未指定 branchId → 默认当前 activeBranch
> 2. 调用 player_action_list 获取结果
> 3. 查询完成后必须调用 finish_action 总结结果
> 4. **不得用普通自然语言直接结束**

## 3. Files Changed

```
Modified:
  src/main/java/com/gsim/agent/OrchestratorAgent.java     (+166 lines)
  src/main/resources/gsim/prompts/orchestrator-system.md  (+18 lines)

New:
  src/main/java/com/gsim/agent/ToolLoopDebug.java         (286 lines, 12 methods)

New tests (7 files, 28 test cases):
  src/test/java/com/gsim/agent/ToolLoopDebugFormatsLlmResponsePreviewTest.java
  src/test/java/com/gsim/agent/ToolLoopDebugFormatsToolExtractionSummaryTest.java
  src/test/java/com/gsim/agent/ToolLoopDebugFormatsFinishActionRejectReasonTest.java
  src/test/java/com/gsim/agent/ToolLoopNoToolRoundsStopsAfterTwoTest.java
  src/test/java/com/gsim/agent/ToolLoopNoToolReminderMentionsFinishActionTest.java
  src/test/java/com/gsim/agent/ToolLoopNoToolReminderMentionsPlayerActionListForActionQueryTest.java
  src/test/java/com/gsim/agent/AgentCurrentTurnActionRecordQueryReturnsResultTest.java
```

## 4. Test Results

```
Tests run: 967, Failures: 0, Errors: 0, Skipped: 0
BUILD SUCCESS
```

### Test breakdown

| # | Test Class | Cases | What It Verifies |
|---|-----------|-------|------------------|
| 1 | `ToolLoopDebugFormatsLlmResponsePreviewTest` | 3 | LLM_RESPONSE log contains rawChars/rawPreview, truncation at 2000, unified TOOL_LOOP prefix |
| 2 | `ToolLoopDebugFormatsToolExtractionSummaryTest` | 3 | TOOL_EXTRACTION contains toolCallCount/tools/containsFinishAction, suspectToolSyntax detection |
| 3 | `ToolLoopDebugFormatsFinishActionRejectReasonTest` | 4 | VALIDATION contains finishAccepted/rejectReason/claim/successTools on both accept and reject paths |
| 4 | `ToolLoopNoToolRoundsStopsAfterTwoTest` | 3 | Abort at 2 consecutive no-tool rounds; tool call resets counter; single round doesn't abort |
| 5 | `ToolLoopNoToolReminderMentionsFinishActionTest` | 3 | Reminder always mentions finish_action; null-safe; mentions business tools |
| 6 | `ToolLoopNoToolReminderMentionsPlayerActionListForActionQueryTest` | 7 | 5 types of action queries trigger player_action_list hint; normal queries don't; isPlayerActionQuery correctness |
| 7 | `AgentCurrentTurnActionRecordQueryReturnsResultTest` | 3 | E2E: player_action_list → finish_action; empty results output "没有行动记录"; plain NL response rejected |

## 5. Key Design Decisions

1. **连续 2 轮（不是 1 轮）**：给 LLM 一次"犯错"机会——第一轮无工具可能是模型对复杂指令的"思考"输出，第二轮再无工具才判死。这个数字是经验值，可以从 2 调成其他值。

2. **Debug 日志不走 event bus：** 直接 `log.debug()`，不经过 EventBus / EventSink。Debug 日志是给开发者的，不是给用户的。event bus 上的事件是给 SSE/CLI 的。

3. **isPlayerActionQuery 用简单关键词匹配**：不上 LLM intent 分类器。原因：(a) 零延迟 (b) 确定性 (c) 这个判断只影响提醒文案，不影响控制流——即使误判，LLM 读取的是完整 conversation history，不会被误导。

4. **prompt 新增 player_action_list 强制规则而非修改 finish_action 规则**：两个规则正交——finish_action 是"如何结束"，player_action_list 是"遇到什么问题时该查什么"。放在一起会让 prompt 难以维护。

## 6. Rollback Plan

- 如 `consecutiveNoToolRounds >= 2` 过于激进（真实场景 LLM 经常连续 2 轮不出工具），调整阈值至 3：
  ```java
  // OrchestratorAgent.java, both loops
  if (consecutiveNoToolRounds >= 3) {  // was 2
  ```
- 如 debug 日志量过大，关闭 debug level：
  ```xml
  <!-- logback.xml -->
  <logger name="com.gsim.agent" level="INFO"/>
  ```
  或在启动时不传 `-Dlog.level=DEBUG`。

## 7. What This Does NOT Change

- ❌ ToolCallExtractor 解析逻辑
- ❌ validateFinishActionMessage / validateFinishActionClaims 验证逻辑
- ❌ FakeLlmClient 行为
- ❌ MAX_TOOL_ROUNDS (仍为 5，作为最终上限)
- ❌ finish_action 本身的参数和执行
- ❌ 任何业务工具（turn_settlement_save_last_response, branch_next_turn, knowledge_upsert, player_action_* 等）
- ❌ DB / import / HTTP API / SSE / WebUI
