# GSimulator 代码核验请求 — commit 26a1efd

> 请作为 code reviewer 核验以下 Java 21 + Maven 多 Agent 推演引擎的代码改动。阅读完所有信息后，给出分级反馈。

---

## 1. 项目背景

- **项目**: GSimulator — 基于 Java 21 的回合制文游推演系统 (CLI REPL + HTTP API)
- **分支**: `phase-root-workspace-governance`
- **当前 commit**: `26a1efd` (base: `f9ba71d`)

## 2. 本次改动概述

**修复 `CliAgentProgressSink` 中 finish_action 拒绝原因的文案映射 bug。**

上一版 commit `f9ba71d` 引入了一个错误：`format()` 方法在 `FINISH_ACTION_REJECTED` 分支中调用 `reasonText(event.detail())`，但 `event.detail()` 返回的是**完整中文句子**（如 `"finish_action 被拒绝：FINISH_ACTION_WITH_OTHER_TOOLS，正在要求模型重写最终回复。"`），而非 reason code。这导致 CLI 输出出现双重前缀 + reason code 裸泄漏。

正确的 reason code 存储在 `event.meta().get("rejectReason")` 中。

## 3. 改动文件清单

| 文件 | 增/删 | 说明 |
|------|-------|------|
| `CliAgentProgressSink.java` | +2/-1 | `reasonText(event.detail())` → `reasonText(event.meta().getOrDefault("rejectReason", ""))` |
| `CliProgressShowsFinishActionRejectedReasonTest.java` | +32/-3 | 改写断言为中文文案 + 新增 2 测试 |

## 4. 关键 Diff

### 4.1 源码修复

```java
// 变更前（错误）:
case AgentProgressEvent.FINISH_ACTION_REJECTED ->
        "[Agent] finish_action 被拒绝：" + reasonText(event.detail());
// event.detail() 返回 "finish_action 被拒绝：FINISH_ACTION_WITH_OTHER_TOOLS，正在要求模型重写最终回复。"
// reasonText() 不匹配任何 switch case → default 分支返回原文
// 最终输出: "[Agent] finish_action 被拒绝：finish_action 被拒绝：FINISH_ACTION_WITH_OTHER_TOOLS，正在要求模型重写最终回复。"
// 问题: 双重前缀 + reason code 裸泄漏给 CLI 用户

// 变更后（修复）:
case AgentProgressEvent.FINISH_ACTION_REJECTED ->
        "[Agent] finish_action 被拒绝：" + reasonText(
                event.meta().getOrDefault("rejectReason", ""));
// event.meta().get("rejectReason") 返回 "FINISH_ACTION_WITH_OTHER_TOOLS"
// reasonText() 匹配 → 返回 "与其他工具同轮混用，要求模型先单独执行工具。"
// 最终输出: "[Agent] finish_action 被拒绝：与其他工具同轮混用，要求模型先单独执行工具。"
```

### 4.2 测试修复

```java
// 变更前 — 断言裸 reason code 存在于输出:
assertTrue(line.contains("CLAIM_WITHOUT_SUPPORTING_TOOL_RESULT"),
        "Should include reject reason: " + line);

// 变更后 — 断言中文文案，同时断言裸 code 不存在:
assertTrue(line.contains("声称了未经真实工具执行支持的结果"),
        "Should include human-readable reject reason: " + line);
assertFalse(line.contains("CLAIM_WITHOUT_SUPPORTING_TOOL_RESULT"),
        "Should NOT leak raw reason code to CLI: " + line);

// 新增 2 个测试:
// - mixedToolsShowsChineseText: FINISH_ACTION_WITH_OTHER_TOOLS → "与其他工具同轮混用"
// - unknownReasonShowsRawText: message validation 错误原文直接透出
```

## 5. reasonText() 映射表

| meta.rejectReason | CLI 输出 |
|-------------------|---------|
| `FINISH_ACTION_WITH_OTHER_TOOLS` | 与其他工具同轮混用，要求模型先单独执行工具。 |
| `CLAIM_WITHOUT_SUPPORTING_TOOL_RESULT` | 声称了未经真实工具执行支持的结果。 |
| 其他（message validation 错误） | 原文透出 |
| null/空 | 未知原因 |

## 6. 架构约束校验

- [x] 不改 DB / import / WebUI / HTTP API
- [x] 不改 Branch storage / KnowledgeStore / PlayerAction
- [x] 不改业务工具逻辑 / tools schema / systemPrompt
- [x] 纯 presentation 层修复，无副作用

## 7. 测试结果

```
Tests run: 1122, Failures: 0, Errors: 0, Skipped: 0
BUILD SUCCESS
```

## 8. 核验要点

1. **`rejectReason` 在所有调用路径上是否保证写入 meta？** — `AgentProgressEvent.finishRejected()` 工厂方法通过 `Map.of("rejectReason", rejectReason != null ? rejectReason : "")` 保证写入。但若有代码直接 `new AgentProgressEvent(...)` 绕过工厂方法，meta 中可能缺少该 key → `getOrDefault` 兜底返回 `""` → `reasonText("")` → `default` 分支返回 `""` → 输出 `"[Agent] finish_action 被拒绝："` 无具体原因。检查是否有直接 new 的调用点。
2. **`reasonText` default 分支是否正确？** — 对 message validation 错误（如 `"finish_action 消息不能包含 [工具结果] 占位符"`），default 分支直接透出原文，不损失信息。但长度可能超过 120 chars。
3. **测试覆盖度** — 现有 5 个测试覆盖了 CLAIM、MIXED、unknown、null、length 五个场景，覆盖充分。

---

## 9. 输出格式

请按以下结构给出反馈：

### 🔴 必须修 (blocking)
如果发现会导致运行时异常、安全漏洞、数据丢失、架构违规的问题。

### 🟡 建议修 (non-blocking)
代码质量、可维护性、性能、命名风格上的改进建议。

### 🟢 确认安全 (verified)
逐项确认没有问题的领域。

### 📋 总结建议
一句话总结 + 是否可以合入 main。
