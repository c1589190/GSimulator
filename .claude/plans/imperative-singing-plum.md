# P0：finish_action 终止语义收口 — 修复 3 个漏洞

## Context

当前 finish_action 已是 ToolLoop 的结束条件，但存在 3 个语义漏洞：

1. **同轮混用未拒绝**：当 LLM 在同一轮返回 `[player_action_list, finish_action]`，所有工具都会执行，
   且 finish_action 被照常接受。finish_action 是终止符，不得与业务工具同轮混用。

2. **runSimToolLoop 缺少 progress 事件**：finish_action 拒绝时，sim loop 没有发送
   `finishRejected` 进度事件，CLI 用户看不见拒绝原因。

3. **accepted 后 break 而非 return**：finish_action accepted 后 break 退出 while，但共享后续
   return 路径，存在继续走 tool_result 回灌或下一轮 LLM 的风险。

## 禁止事项

不改：DB、import、WebUI、HTTP API、Branch storage、KnowledgeStore、PlayerAction、
业务工具逻辑、工具路由策略、权限确认机制、tools schema 子集选择、systemPrompt 瘦身、上下文加载策略。

## 修改文件

### 1. `OrchestratorAgent.java` — 核心逻辑

#### 1a. 同轮混用拒绝（runToolLoop + runSimToolLoop）

在 `if (!allParsed.isEmpty())` 块最前面，执行任何工具之前：

```java
boolean hasFinishAction = allParsed.stream().anyMatch(p -> "finish_action".equals(p.tool));
boolean hasOtherTool = allParsed.stream().anyMatch(p -> !"finish_action".equals(p.tool));
if (hasFinishAction && hasOtherTool) {
    progressSink.onProgress(AgentProgressEvent.finishRejected(
            toolRound, MAX_TOOL_ROUNDS, "FINISH_ACTION_WITH_OTHER_TOOLS"));
    String toolsList = allParsed.stream().map(ParsedToolCall::tool)
            .collect(Collectors.joining(", "));
    String rejection = "[系统] finish_action 必须是本轮唯一工具调用。"
            + "检测到同时包含 finish_action 和其他工具（" + toolsList + "）。"
            + "请先单独执行非 finish_action 工具，收到工具结果后，在下一轮单独调用 finish_action。";
    // 优先使用 system 角色回灌纠错；不支持则降级为 user
    messages.add(LlmMessage.system(rejection));
    trace.add(new MessageTrace("system", "finish_rejected", "FINISH_ACTION_WITH_OTHER_TOOLS"));
    ToolLoopDebug.logFinishActionWithOtherToolsRejected(log, loopName, toolRound, allParsed);
    toolRound++;
    continue;
}
```

#### 1b. accepted 后直接 return ChatResult

在 finish_action 验证通过后，不要 `break`，改为直接 `return`：

```java
// finish_action accepted 路径：
ToolLoopDebug.logFinalText(log, loopName, "finish_action", finalText);
return new ChatResult(true, finalText, toolCalls, trace, null);
```

需要修改的位置（runToolLoop 和 runSimToolLoop）：
- API_TOOL_CALLS 路径中 finish_action 单独调用的 accepted 分支
- TEXT_FALLBACK 路径中 finish_action 单独调用的 accepted 分支
- runSimToolLoop 中的对应路径

round 保持一致：所有 finish_action 日志使用同一 `toolRound` 值，不要 +1。

#### 1c. runSimToolLoop 补 progress + accepted 改 direct return

在 runSimToolLoop 的 finish_action 验证失败路径补齐 progress：
```java
// message 校验失败
progressSink.onProgress(AgentProgressEvent.finishRejected(
        toolRound, MAX_TOOL_ROUNDS, reasonFromMessageError(validationError)));

// claim 校验失败
progressSink.onProgress(AgentProgressEvent.finishRejected(
        toolRound, MAX_TOOL_ROUNDS, "CLAIM_WITHOUT_SUPPORTING_TOOL_RESULT"));
```

runSimToolLoop 中 finish_action accepted 路径同样改 direct return：

```java
// finish_action 验证通过后，立即记录 FINAL_TEXT 来源，然后直接返回：
ToolLoopDebug.logFinalText(log, loopName, "finish_action", finalText);
return ChatResult.success(finalText, trace); // 按项目真实构造方法替换
```

不要 `break` 后继续走 tool_result 回灌或下一轮 LLM。

### 2. `ToolLoopDebug.java` — 新增混合拒绝 debug log

```java
static void logFinishActionWithOtherToolsRejected(Logger log, String loopName,
        int round, List<OrchestratorAgent.ParsedToolCall> allParsed) {
    // === TOOL_LOOP FINISH_ACTION ===
    // finishAccepted=false
    // rejectReason=FINISH_ACTION_WITH_OTHER_TOOLS
    // allTools=[...]
    // === TOOL_LOOP FINISH_ACTION END ===
}
```

### 3. `FakeLlmClient.java` — 请求计数

如果还没有 `getRequestCount()`，添加：
```java
public int getRequestCount() { return capturedRequests.size(); }
```

### 4. `CliAgentProgressSink.java` — 新 rejectReason 格式化

在 `format()` 的 `FINISH_ACTION_REJECTED` case 中，新增 `FINISH_ACTION_WITH_OTHER_TOOLS` 格式化：
```
[Agent] finish_action 被拒绝：与其他工具同轮混用，要求模型先单独执行工具。
```

## 测试（8 个）

1. **FinishActionFromApiToolCallsImmediatelyEndsLoopTest** — API_TOOL_CALLS `[finish_action]`，finalText="收到。"，requestCount==1
2. **FinishActionFromTextFallbackImmediatelyEndsLoopTest** — TEXT_FALLBACK finish_action，requestCount==1
3. **FinishActionAcceptedDoesNotTriggerNextLlmRequestTest** — R1 player_action_list → R2 finish_action → requestCount==2（无 R3）
4. **FinishActionIsNotTreatedAsOrdinaryToolTest** — finish_action 执行后无 tool_result 回灌
5. **FinishActionWithOtherToolsInSameRoundIsRejectedTest** — `[player_action_list, finish_action]` → 0 个工具执行，progress 收到 FINISH_ACTION_REJECTED
6. **FinishActionRejectedShowsReasonAndContinuesTest** — R1 finish_action 含 `[工具结果]` 被拒 → R2 合法 finish_action → 结束
7. **FinishActionRejectedThenValidFinishEndsLoopTest** — R1 被拒 → R2 合法 → finalText 来自 R2
8. **ToolLoopFinalTextSourceIsFinishActionTest** — 完整流程，断言 FINAL_TEXT source=finish_action

## 验证

```bash
mvn test      # 现有测试 + 8 新测试全部通过
mvn package   # BUILD SUCCESS
git status --short
```
