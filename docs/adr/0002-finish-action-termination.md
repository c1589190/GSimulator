# ADR 0002: finish_action 作为强制 ToolLoop 终止信号

**Status:** Accepted

## Context

早期版本的 Agent 系统允许 LLM 在不发送显式终止信号的情况下结束输出。`ToolLoop` 依靠启发式规则来判断 Agent 是否完成：

- 检测到自然语言叙事文本 → 认为"已完成"
- 检测到空 tool call → 认为"出错"
- 达到 `maxToolRounds` → 认为"失败"

这些启发式规则在实践中被证明不可靠。Agent 有时会在任务中途停止输出（模型认为"够了"但工作未做完），有时会在完成后继续输出多余的 tool call。系统没有任何方式区分"我已完成"和"我正在思考"这两种状态，导致后续审计困难、SubAgent 结果不可靠、以及用户体验不一致。

`ARCHITECTURE.md` 中定义的"可审计、可回放、可扩展的回合制推演"目标要求 Agent 的每次终止都能被精确记录和验证。

## Decision

**`finish_action` 是 ToolLoop 唯一允许的终止路径。** 循环仅在以下三种情况下结束：

1. **成功** — LLM 调用 `finish_action` 工具，验证通过后循环正常终止。
2. **失败** — 耗尽了 `maxToolRounds`，循环以超限失败终止。
3. **中断** — 用户按下 ESC，循环以外部中断终止。

`finish_action` 的 `message` 参数在输出前经过严格的多层校验，确保 Agent 真正完成了输出而并非在伪造结果：

- **占位符检测** — 拒绝 `[工具调用已执行]` 这类模型忘记替换的残留文本。
- **人工制品标记检测** — 拒绝 `[工具结果]` / `[TOOL_RESULT]` 等模型虚构的工具输出标记。
- **fenced JSON 检测** — 拒绝 ```json{"tool":"...","args":{...}}``` 格式的残余 tool call。
- **裸 JSON 检测** — 拒绝 `{"tool":"...","args":{...}}` 格式的残余 tool call。
- **`{key=value}` 伪造模式检测（MODEL_FAKE_TOOL_RESULT）** — 拒绝模型伪造工具输出结果的行为。

校验失败时，错误信息被反馈给 LLM 要求重写消息，**不消耗额外一次轮次配额**。这避免了对模型"犯错"的过度惩罚，同时保证输出质量。

## Consequences

1. **清晰可审计的终止记录** — 每个 Agent 对话都以一条显式的 `finish_action` 记录结束，包含完整的最终消息。`AgentResult` 中的 `AgentRound` 列表精确记录了循环中的每一步，便于回放和调试。
2. **Prompt 训练开销** — 所有 Agent 的 system prompt 必须包含 `finish_action` 的调用规则，并需要明确训练模型在何时应当（和不应当）调用它。这增加了 prompt 编写的复杂度。
3. **校验规则需要持续维护** — LLM 的行为模式随模型版本变化，`MODEL_FAKE_TOOL_RESULT` 等检测模式需要跟随模型进化不断补充新的伪造模式。
4. **SubAgent 的一致性** — SubAgent 同样使用 `finish_action` 终止，其结果通过 `dispatch_sub_agent` 的同步返回值传播到父 Agent，整个调用栈的终止语义保持一致。
