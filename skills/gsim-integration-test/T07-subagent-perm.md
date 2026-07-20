# T07: 子 Agent 创建/派发/权限边界

## 前置条件

- T01 通过（服务可用，LLM provider 已配置）
- 当前 World 为 test_integration（如不在，先切换）

## 测试步骤

### Step 1: 列出已有 Agent 配置

- **工具**: `list_agent_config`
- **参数**: 无
- **预期结果**: 返回 orchestrator、sim、search 等内置 agent

```json
{"tool": "list_agent_config", "args": {}}
```

### Step 2: 创建只读 SubAgent

- **工具**: `create_sub_agent_config`
- **参数**:
  - `agent_id`: `test_agent_reader`
  - `llm_provider`: `base`
  - `system_prompt`: `你是一个测试用的只读 Agent。你只能使用 READ 权限的工具。你的任务是根据输入问题，查询 World 中的数据并给出回答。不要尝试写入任何数据。`
  - `temperature`: `0.3`
  - `max_tokens`: `1024`
  - `max_tool_rounds`: `4`
  - `tool_filter`: `read_only`
- **预期结果**: Agent 配置创建成功

```json
{"tool": "create_sub_agent_config", "args": {"agent_id": "test_agent_reader", "llm_provider": "base", "system_prompt": "你是一个测试用的只读 Agent。你只能使用 READ 权限的工具。你的任务是根据输入问题，查询 World 中的数据并给出回答。不要尝试写入任何数据。", "temperature": 0.3, "max_tokens": 1024, "max_tool_rounds": 4, "tool_filter": "read_only"}}
```

### Step 3: 创建受限写入 SubAgent

这个 Agent 有 read_only + 特定工具的白名单（allowList）。

> **注意**: `allowList` 是 AgentConfig 级别的字段。如果当前 `create_sub_agent_config` 工具不支持该参数，先用 read_only 创建，再通过 `update_sub_agent_config` 补充。

- **工具**: `create_sub_agent_config`
- **参数**:
  - `agent_id`: `test_agent_writer`
  - `llm_provider`: `base`
  - `system_prompt`: `你是一个测试用的受限写入 Agent。你只能使用 READ 权限的工具 + write_element 工具。你可以在 characters checkpoint 下写入测试数据。`
  - `temperature`: `0.3`
  - `max_tokens`: `1024`
  - `max_tool_rounds`: `6`
  - `tool_filter`: `custom`
  - `tool_allow_list`: `["query_element", "query_node", "query_checkpoint", "query_keyword", "write_element", "node_list", "node_status", "finish_action"]`
- **预期结果**: Agent 配置创建成功

```json
{"tool": "create_sub_agent_config", "args": {"agent_id": "test_agent_writer", "llm_provider": "base", "system_prompt": "你是一个测试用的受限写入 Agent。你只能使用 READ 权限的工具 + write_element 工具。你可以在 characters checkpoint 下写入测试数据。", "temperature": 0.3, "max_tokens": 1024, "max_tool_rounds": 6, "tool_filter": "custom", "tool_allow_list": ["query_element", "query_node", "query_checkpoint", "query_keyword", "write_element", "node_list", "node_status", "finish_action"]}}
```

### Step 4: 验证 Agent 配置

- **工具**: `list_agent_config`
- **参数**: 无
- **预期结果**: 列表中包含 test_agent_reader 和 test_agent_writer

```json
{"tool": "list_agent_config", "args": {}}
```

### Step 5: 派发只读 Agent（dispatch_sub_agent）

- **工具**: `dispatch_sub_agent`
- **参数**:
  - `agentId`: `test_agent_reader`
  - `prompt`: `查询 test_integration 世界中 characters checkpoint 下的"测试角色"元素，总结该角色的信息。`
- **预期结果**: SubAgent 执行成功，返回角色信息摘要（含"艾尔文爵士"相关内容）

```json
{"tool": "dispatch_sub_agent", "args": {"agentId": "test_agent_reader", "prompt": "查询 test_integration 世界中 characters checkpoint 下的\"测试角色\"元素，总结该角色的信息。"}}
```

### Step 6: 派发受限写入 Agent

- **工具**: `dispatch_sub_agent`
- **参数**:
  - `agentId`: `test_agent_writer`
  - `prompt`: `在 test_integration 世界的 characters checkpoint 中写入一个新元素 "测试角色2"，值为"名称：测试副官\n身份：艾尔文的副手"，tags 设为 "测试,Character"。使用 write_element 工具。`
- **预期结果**: SubAgent 成功写入新角色元素

```json
{"tool": "dispatch_sub_agent", "args": {"agentId": "test_agent_writer", "prompt": "在 test_integration 世界的 characters checkpoint 中写入一个新元素 \"测试角色2\"，值为\"名称：测试副官\\n身份：艾尔文的副手\"，tags 设为 \"测试,Character\"。使用 write_element 工具。"}}
```

### Step 7: 验证写入结果

- **工具**: `query_element`
- **参数**: `worldId`: `test_integration`, `ref`: `characters:测试角色2`
- **预期结果**: 存在"测试角色2"元素

```json
{"tool": "query_element", "args": {"worldId": "test_integration", "ref": "characters:测试角色2"}}
```

### Step 8: 权限越权测试 — dispatch_sub_agent 的 toolFilter 门禁

验证 read_only Agent 不能执行写入操作。

- **工具**: `dispatch_sub_agent`
- **参数**:
  - `agentId`: `test_agent_reader`
  - `prompt`: `尝试在 test_integration 世界中写入一个名为 "越权测试" 的元素到 characters checkpoint。使用 write_element 工具。`
- **预期结果**: SubAgent 执行失败，因为 test_agent_reader 的 toolFilter=read_only，write_element 的权限为 WRITE，被门禁拦截

```json
{"tool": "dispatch_sub_agent", "args": {"agentId": "test_agent_reader", "prompt": "尝试在 test_integration 世界中写入一个名为 \"越权测试\" 的元素到 characters checkpoint。使用 write_element 工具。"}}
```

### Step 9: 权限越权测试 — maxPermission 限制

验证即使显式要求，Agent 的 maxPermission 也限制其调用更高权限工具。

- **工具**: `dispatch_sub_agent`
- **参数**:
  - `agentId`: `test_agent_reader`
  - `prompt`: `尝试删除 test_integration 世界中的 "测试角色2" 元素。使用 delete_element 工具。`
- **预期结果**: SubAgent 无法调用 delete_element（权限为 SYSTEM），因为 maxPermission=READ 的门禁拦截

```json
{"tool": "dispatch_sub_agent", "args": {"agentId": "test_agent_reader", "prompt": "尝试删除 test_integration 世界中的 \"测试角色2\" 元素。使用 delete_element 工具。"}}
```

### Step 10: 查看 SubAgent 缓存

- **工具**: `list_sub_agent_caches`
- **参数**: `worldId`: `test_integration`
- **预期结果**: 至少包含之前 dispatch 产生的 1+ 个缓存条目，每个有 sessionId 和时间戳

```json
{"tool": "list_sub_agent_caches", "args": {"worldId": "test_integration"}}
```

### Step 11: 更新 Agent 配置

修改 test_agent_reader 的 temperature 和 max_tokens。

- **工具**: `update_sub_agent_config`
- **参数**:
  - `agent_id`: `test_agent_reader`
  - `temperature`: `0.7`
  - `max_tokens`: `2048`
- **预期结果**: 配置更新成功

```json
{"tool": "update_sub_agent_config", "args": {"agent_id": "test_agent_reader", "temperature": 0.7, "max_tokens": 2048}}
```

### Step 12: 收集 SubAgent 结果

如有之前 dispatch 的异步结果尚未收集。

- **工具**: `collect_sub_agent_results`
- **参数**: `worldId`: `test_integration`
- **预期结果**: 返回未收集的 SubAgent 结果列表

```json
{"tool": "collect_sub_agent_results", "args": {"worldId": "test_integration"}}
```

## 预期通过标准

- [ ] Step 1: 内置 Agent 配置列表可查
- [ ] Step 2: test_agent_reader 创建成功
- [ ] Step 3: test_agent_writer 创建成功
- [ ] Step 4: 列表包含两个新 Agent
- [ ] Step 5: dispatch read_only Agent 成功，返回角色信息
- [ ] Step 6: dispatch 受限写入 Agent 成功写入
- [ ] Step 7: 写入结果可验证
- [ ] Step 8: 越权写入被门禁拦截
- [ ] Step 9: 越权删除被 maxPermission 拦截
- [ ] Step 10: SubAgent 缓存列表可查
- [ ] Step 11: Agent 配置可热更新
- [ ] Step 12: collect 结果正常

## 失败排查提示

| 症状 | 可能原因 | 排查动作 |
|------|---------|---------|
| dispatch 超时 120s | LLM API 不可达 | 检查 LLM provider 配置和网络 |
| dispatch 返回空 | SubAgent 执行异常 | 查看 SubAgent 缓存中的错误日志 |
| 越权测试的 Agent 仍成功写入 | toolFilter 未生效 | 检查 ToolFilterEvaluator.allowsWithPermission 逻辑 |
| update_sub_agent_config 未生效 | reload 机制问题 | 确认 AgentConfigManager 触发了 reload |
| create_sub_agent_config 报 "agent exists" | 上次测试残留 | 先执行 T13 清理 |

## 扩展测试（可选）

- **E7.1**: 并行 dispatch 两个 Agent 验证无竞争条件
- **E7.2**: dispatch 时使用 cacheId 参数续接之前上下文
- **E7.3**: 创建 maxPermission=WRITE 的 Agent，验证可以写但不能删
- **E7.4**: agent_cancel 取消运行中的 SubAgent
- **E7.5**: 删除 Agent 配置后 dispatch 应返回明确的错误信息
