# P0 缺陷修复报告：长篇结算正文通过 raw JSON 传输导致 ToolLoop 失效

**日期**：2026-06-19  
**提交**：f6c0799（前置）→ 本报告覆盖的后续修复  
**严重级别**：P0 — 核心工作流不可用  
**状态**：已修复，910 测试通过，BUILD SUCCESS

---

## 1. 问题描述

### 1.1 现象

Agent 在执行"结算当前回合并进入下一回合"时，虽然检测到了 `MODEL_FAKE_TOOL_RESULT` 并启用了守卫逻辑，但仍无法完成完整工作流。具体表现：

- Agent 生成了数千字结算正文
- 试图通过 `turn_settlement_save` 的 JSON 参数传递：`{"tool":"turn_settlement_save","args":{"settlement":"几千字正文…"}}`
- 在第三方 OpenAI-compatible API 上，长 JSON 参数导致截断、转义失败、fenced JSON 泄露
- `ToolCallExtractor` 无法解析损坏的 tool call，ToolLoop 提前退出
- Agent 声称"已保存"、"已进入下一回合"，但实际没有执行任何工具

### 1.2 根因

```
LLM 输出:  自然语言结算正文 + ```json{"tool":"turn_settlement_save","args":{"settlement":"长篇正文..."}}```
                    ↓
第三方 API 降级: 长 JSON arg 被截断/转义破坏
                    ↓
ToolCallExtractor: 无法解析 → ToolLoop 退出
                    ↓
finalText: 仍包含 fenced JSON / 成功宣称 → guard 触发但不解决根本问题
```

**核心矛盾**：JSON 工具调用参数不适合传输千字级自然语言正文。这在 OpenAI 官方 API 上可能凑巧工作，但在任何 OpenAI-compatible 第三方代理上都是不可靠的。

---

## 2. 解决方案

### 2.1 总体思路

**将"正文"与"工具调用"解耦**：
- 正文通过聊天消息的自然语言部分传输（LLM 的强项）
- 工具调用只携带短参数（branchId、inputSummary）
- 工具执行时从"缓存的最新 assistant 回复"中读取正文

### 2.2 架构变更

```
修改前：
  LLM → 长篇正文嵌入 JSON args → ToolCall → 解析失败 → 流程断裂

修改后：
  LLM → 自然语言正文（聊天消息体）
     ↓
  ToolLoop 缓存正文为 lastAssistantDraft（剥离 fenced JSON / 伪造标记）
     ↓
  LLM → fenced JSON tool call（仅短参数：inputSummary, branchId）
     ↓
  turn_settlement_save_last_response.execute() → 读取 lastAssistantDraft → 保存
     ↓
  LLM → branch_next_turn（短参数）
     ↓
  最终自然语言回复
```

---

## 3. 具体变更

### 3.1 OrchestratorAgent.java（核心改动）

| 变更 | 说明 |
|------|------|
| 新增 `AtomicReference<String> lastAssistantDraft` | 跨轮次缓存最新 assistant 自然语言输出 |
| 新增 `getLastAssistantDraft()` | 供 Tool 通过 Supplier 延迟读取 |
| 新增 `toCleanDraft(String)` 静态方法 | 组合 `stripRawToolJson()` + `stripFakeBracketToolResult()` |
| `runToolLoop()` 内新增 draft 缓存 | 每轮 assistant 回复到达后立即缓存（tool 执行前），使用 `toCleanDraft()` |
| `runSimToolLoop()` 内新增 draft 缓存 | 同上 |
| `runToolLoop()` 返回前更新 draft | `lastAssistantDraft.set(finalText)` — post-guard 值 |
| `runSimToolLoop()` 返回前更新 draft | 同上 |

**关键时序**：
```java
// 在 tool extraction 之前缓存（确保持有当前轮的自然语言部分）
String strippedForDraft = toCleanDraft(content);
if (!strippedForDraft.isBlank()) {
    lastAssistantDraft.set(strippedForDraft);
}
// 然后才是 tool extraction + execution
List<ParsedToolCall> allParsed = ToolCallExtractor.extractAllToolCalls(content);
```

### 3.2 TurnSettlementSaveLastResponseTool.java（新文件）

```
路径: src/main/java/com/gsim/branch/tool/TurnSettlementSaveLastResponseTool.java

特点:
- 工具名: turn_settlement_save_last_response
- 短参数: branchId (可选), inputSummary (可选短摘要), title (可选)
- 正文来源: 构造时注入 Supplier<String> → 延迟读取 OrchestratorAgent.getLastAssistantDraft()
- 保存逻辑: 复用 BranchFileSimContent.saveTurnSettlement()，与 turn_settlement_save 一致
- 错误处理:
  - NO_ACTIVE_ROOT → 无活跃根节点
  - NO_LAST_ASSISTANT_DRAFT → 草稿为空（引导 LLM 先生成正文）
  - SAVE_FAILED → 文件写入异常
```

### 3.3 GSimulatorApplication.java（1 行）

注册新工具，绑定 `orchestrator::getLastAssistantDraft` 为 Supplier：
```java
toolRegistry.register(new com.gsim.branch.tool.TurnSettlementSaveLastResponseTool(
        dataManager, orchestrator::getLastAssistantDraft));
```

### 3.4 orchestrator-system.md（Prompt 规则）

新增规则块：

1. **推演内容保存规则** — 第 8 条改为优先使用 `turn_settlement_save_last_response`
2. **结算 + 下一回合标准流程** — 5 步强制顺序（读上下文 → 生成结算 → save_last_response → branch_next_turn → 自然语言总结）
3. **不得在保存失败时进入下一回合** — 两个否定规则
4. **禁止使用长文本 JSON args 保存结算** — 4 条禁止项

---

## 4. 测试覆盖

### 4.1 测试清单（7 文件，27 测试）

| 测试类 | 数量 | 类型 | 验证点 |
|--------|------|------|--------|
| `TurnSettlementSaveLastResponseToolSavesDraftTest` | 3 | 单元+集成 | 保存到 branch 文件、settlementId 递增、title 覆盖 |
| `TurnSettlementSaveLastResponseRejectsEmptyDraftTest` | 4 | 单元 | 空/null/空白 draft 拒绝、无 root 拒绝 |
| `ToolLoopCachesLastAssistantDraftForSettlementTest` | 3 | 集成 | draft 非空、不含工具 JSON、跨调用更新 |
| `ToolLoopStripsRawToolJsonFromDraftBeforeSaveTest` | 6 | 单元+集成 | 剥离 fenced JSON、裸 JSON、[工具结果]、伪造 KV 块、干净文本不变 |
| `AgentSettlementThenNextTurnUsesSaveLastResponseAndBranchNextTurnTest` | 2 | 端到端 | 完整流程（结算+save+next turn）、最终回复不含警告 |
| `AgentDoesNotEnterNextTurnIfSettlementSaveFailsTest` | 3 | 端到端 | 失败阻断 next turn、工具调用记录、诚实报告不触发 guard |
| `ToolLoopDoesNotExposeLongTurnSettlementSaveJsonTest` | 6 | 端到端 | finalText 不含 fenced JSON / 裸 JSON / [工具结果] / 系统警告；draft 清洁验证 |

### 4.2 测试技术细节

- **FakeLlmClient** — 预设多轮回复序列，模拟组合响应（正文 + fenced tool call）
- **Stub Tool 模式** — 用 `AtomicReference<String>` 捕获工具执行时的 draft 值
- **Supplier 延迟求值** — 解决 stub 注册时 agent 尚未初始化的 chicken-and-egg 问题
- **组合响应测试** — 单条响应同时包含正文和 fenced JSON，验证 ToolLoop 正确拆分
- **@TempDir** — 集成测试使用临时目录隔离文件系统副作用

---

## 5. 验证结果

```
mvn test   → Tests run: 910, Failures: 0, Errors: 0, Skipped: 0
mvn package → BUILD SUCCESS
```

---

## 6. 影响面分析

### 6.1 向后兼容

- **`turn_settlement_save` 保留** — 当需要同时传入 worldDelta/entityDelta/ruleDelta/risk 时仍可使用
- **ToolLoop 行为不变** — draft 缓存是纯增量逻辑，不影响现有工具提取和执行流程
- **Prompt 仅追加规则** — 不影响已有推演指令

### 6.2 潜在风险

| 风险 | 缓解 |
|------|------|
| LLM 可能仍尝试旧工具 | Prompt 明确优先级，`description()` 引导模型选择 |
| draft 在 tool 执行前被覆盖 | `AtomicReference` + 每轮缓存确保时序正确 |
| draft 包含 fenced JSON 残留 | `toCleanDraft()` 组合两个剥离函数 |
| 工具执行后 draft 被下轮覆盖（测试误判） | 测试使用 stub 捕获工具执行时的值，而非事后读取 |

### 6.3 修改文件总览

```
修改:
  src/main/java/com/gsim/agent/OrchestratorAgent.java     (+45/-2)
  src/main/java/com/gsim/app/GSimulatorApplication.java    (+2)
  src/main/resources/gsim/prompts/orchestrator-system.md   (+20/-2)

新增:
  src/main/java/com/gsim/branch/tool/TurnSettlementSaveLastResponseTool.java  (125 行)
  src/test/java/com/gsim/branch/tool/TurnSettlementSaveLastResponseToolSavesDraftTest.java
  src/test/java/com/gsim/branch/tool/TurnSettlementSaveLastResponseRejectsEmptyDraftTest.java
  src/test/java/com/gsim/agent/ToolLoopCachesLastAssistantDraftForSettlementTest.java
  src/test/java/com/gsim/agent/ToolLoopStripsRawToolJsonFromDraftBeforeSaveTest.java
  src/test/java/com/gsim/agent/AgentSettlementThenNextTurnUsesSaveLastResponseAndBranchNextTurnTest.java
  src/test/java/com/gsim/agent/AgentDoesNotEnterNextTurnIfSettlementSaveFailsTest.java
  src/test/java/com/gsim/agent/ToolLoopDoesNotExposeLongTurnSettlementSaveJsonTest.java
```

---

## 7. 设计要点（reviewer 注意）

1. **为什么用 `AtomicReference<String>` 而非方法参数传递？**  
   Tool 接口是 `execute(ToolCall)` → `ToolResult`，无法接受额外参数。在 ToolRegistry 注册时注入 Supplier 是最小侵入方式。

2. **为什么缓存两处（tool 执行前 + post-guard）？**  
   - 执行前缓存：确保 `turn_settlement_save_last_response` 在当前轮就能读到正文（LLM 在同一轮输出正文 + fenced tool call）  
   - post-guard 缓存：后续轮次如果没有新 tool call（纯自然语言回复），draft 也有值

3. **`toCleanDraft()` vs `stripRawToolJson()` 的区别？**  
   `stripRawToolJson` 只剥离 fenced JSON 块和裸 JSON tool call。  
   `toCleanDraft` 额外调用 `stripFakeBracketToolResult` 剥离 `[工具结果]`、`[系统提示]`、伪造的 `{key=value}` 块。  
   两处 draft 缓存点现已统一使用 `toCleanDraft()`。

4. **为什么不让 LLM 先输出正文再单独输出 tool call？**  
   理论上更干净，但多一轮往返增加延迟和 token 消耗。组合响应（正文 + fenced JSON）让 LLM 在单轮完成"表达意图 + 触发动作"，ToolLoop 负责拆分。
