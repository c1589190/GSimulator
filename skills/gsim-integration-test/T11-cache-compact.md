# T11: 缓存与压缩

## 前置条件

- T07 通过（已执行过 dispatch_sub_agent，产生了 SubAgent 缓存）

## 测试步骤

### Step 1: 列出 SubAgent 缓存

- **工具**: `list_sub_agent_caches`
- **参数**: `worldId`: `test_integration`
- **预期结果**: 返回缓存列表，至少包含 T07 产生的 1+ 条缓存。每条有 sessionId、创建时间、消息数

```json
{"tool": "list_sub_agent_caches", "args": {"worldId": "test_integration"}}
```

### Step 2: 获取缓存详情

从 Step 1 返回的列表中选择第一个 sessionId。

- **工具**: `view_sub_agent_cache`
- **参数**:
  - `worldId`: `test_integration`
  - `sessionId`: `<从 Step 1 获取>`
  - `offset`: `0`
  - `limit`: `10`
- **预期结果**: 返回缓存对话的前 10 条消息（system prompt + 用户 prompt + tool calls + tool results + assistant responses）

```json
{"tool": "view_sub_agent_cache", "args": {"worldId": "test_integration", "sessionId": "<SESSION_ID>", "offset": 0, "limit": 10}}
```

### Step 3: 查看 SubAgent 最终输出

- **工具**: `view_sub_agent_output`
- **参数**:
  - `worldId`: `test_integration`
  - `sessionId`: `<从 Step 1 获取>`
- **预期结果**: 返回该 SubAgent 会话的最终文本输出（不含内部 tool call 细节）

```json
{"tool": "view_sub_agent_output", "args": {"worldId": "test_integration", "sessionId": "<SESSION_ID>"}}
```

### Step 4: 缓存文本编辑（仅 MCP）

如通过 MCP 路径，可使用 `gsim_cache_edit` 编辑缓存文本。

- **MCP 工具**: `mcp__gsimap__gsim_cache_edit`
- **参数**:
  - `cacheId`: `<从缓存列表获取>`
  - `insert_text`: `[压缩摘要] 本次对话涉及 test_integration 世界的角色查询操作。`
- **预期结果**: 文本追加成功，生成新的缓存文件

### Step 5: 列出所有文本缓存

- **MCP 工具**: `mcp__gsimap__gsim_cache_list`
- **参数**: 无
- **预期结果**: 返回所有 `.cache/` 下的文本缓存文件

```json
{"tool": "cache_list", "args": {}}
```

### Step 6: 缓存压缩

对某个较长的 SubAgent 缓存执行压缩（compact）。

- **工具**: `compact_cache`
- **参数**:
  - `worldId`: `test_integration`
  - `sessionId`: `<从 Step 1 获取>`
- **预期结果**: 压缩成功，返回压缩后的摘要或确认信息

```json
{"tool": "compact_cache", "args": {"worldId": "test_integration", "sessionId": "<SESSION_ID>"}}
```

- **验证**: 再次 `view_sub_agent_cache`，确认消息数减少或出现摘要标记

```json
{"tool": "view_sub_agent_cache", "args": {"worldId": "test_integration", "sessionId": "<SESSION_ID>", "offset": 0, "limit": 5}}
```

## 预期通过标准

- [ ] Step 1: 缓存列表含预期条目
- [ ] Step 2: 缓存详情可分页查看
- [ ] Step 3: 最终输出可获取
- [ ] Step 4: 文本缓存编辑可用（MCP 路径）
- [ ] Step 5: 文本缓存列表可用
- [ ] Step 6: 压缩功能正常

## 失败排查提示

| 症状 | 可能原因 | 排查动作 |
|------|---------|---------|
| list_sub_agent_caches 返回空 | 没有执行过 dispatch | 先执行 T07 任意 dispatch 步骤 |
| view_sub_agent_cache 返回空 | sessionId 错误 | 从 list_sub_agent_caches 复制精确 sessionId |
| compact_cache 失败 | LLM 调用失败 | 检查 LLM provider 是否可用 |
| compact 后消息数未减少 | 缓存已经足够短 | 用更长的对话测试压缩效果 |

## 扩展测试（可选）

- **E12.1**: 用 cacheId 参数 dispatch 续接已压缩的对话
- **E12.2**: 多个 SubAgent 并行运行时的缓存隔离
- **E12.3**: 缓存文件的实际磁盘大小验证
