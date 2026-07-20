# T12: 权限矩阵全覆盖

## 前置条件

- T07 通过（test_agent_reader 和 test_agent_writer 已创建）
- 理解四层权限体系：SELF < READ < WRITE < SYSTEM

## 背景

Phase 1 引入的权限系统：
- **SELF**: 流程控制工具（finish_action, activate_tool_groups），始终允许，不受 maxPermission 限制
- **READ**: 所有查询/列表/读取工具（43 个）
- **WRITE**: 修改但不破坏性操作（26 个）
- **SYSTEM**: 破坏性操作 + Agent 管理（9 个）

权限门禁在三个层面生效：
1. **ToolFilterEvaluator.allowsWithPermission()** — 决定 Agent 能否看到工具定义
2. **ToolExecutionPolicy.validateBeforeExecute()** — 决定工具是否能执行
3. **OrchestratorAgent.runSimToolLoop()** — sim 模式的额外权限检查

## 测试步骤

### Step 1: 验证 SELF 工具始终可用

创建 maxPermission=SELF 的极简 Agent 配置。

- **工具**: `create_sub_agent_config`
- **参数**:
  - `agent_id`: `test_agent_self`
  - `llm_provider`: `base`
  - `system_prompt`: `你是一个仅拥有 SELF 级别权限的 Agent。你只能使用 finish_action 和 activate_tool_groups。`
  - `max_tool_rounds`: `2`
  - `tool_filter`: `all`
- **预期结果**: Agent 创建成功。当派发此 Agent 时，它只能看到 finish_action 和 activate_tool_groups（因为 tool_filter=all 但 maxPermission 隐式限制）。

```json
{"tool": "create_sub_agent_config", "args": {"agent_id": "test_agent_self", "llm_provider": "base", "system_prompt": "你是一个仅拥有 SELF 级别权限的 Agent。你只能使用 finish_action 和 activate_tool_groups。", "max_tool_rounds": 2, "tool_filter": "all"}}
```

> **注意**: 如果 `create_sub_agent_config` 不支持 `maxPermission` 参数，则此项验证需在 `update_sub_agent_config` 或代码层面补充。当前可改为验证 read_only 的 Agent 仍然可以看到 SELF 工具。

### Step 2: 验证 READ 级别包含所有读工具

通过 test_agent_reader（toolFilter=read_only）派发，确认它能调用 READ 工具。

- **工具**: `dispatch_sub_agent`
- **参数**:
  - `agentId`: `test_agent_reader`
  - `prompt`: `列出 test_integration 世界的所有节点。使用 node_list 工具。`
- **预期结果**: 成功执行（node_list 权限为 READ）

```json
{"tool": "dispatch_sub_agent", "args": {"agentId": "test_agent_reader", "prompt": "列出 test_integration 世界的所有节点。使用 node_list 工具。"}}
```

### Step 3: 验证 WRITE 级别包含写但排除删除

通过 test_agent_writer（toolFilter=custom，含 write_element）派发，确认能写不能删。

- **工具**: `dispatch_sub_agent`
- **参数**:
  - `agentId`: `test_agent_writer`
  - `prompt`: `在 test_integration 的 characters checkpoint 中写入元素 "test_perm_write"，值为 "权限测试-写入"。使用 write_element。`
- **预期结果**: 成功（write_element 权限为 WRITE，在允许列表中）

```json
{"tool": "dispatch_sub_agent", "args": {"agentId": "test_agent_writer", "prompt": "在 test_integration 的 characters checkpoint 中写入元素 \"test_perm_write\"，值为 \"权限测试-写入\"。使用 write_element。"}}
```

### Step 4: 验证 WRITE 级别不能删除

- **工具**: `dispatch_sub_agent`
- **参数**:
  - `agentId`: `test_agent_writer`
  - `prompt`: `尝试删除 test_integration 中的 "test_perm_write" 元素。使用 delete_element。`
- **预期结果**: 失败（delete_element 权限为 SYSTEM，test_agent_writer 的工具列表中不包含它）

```json
{"tool": "dispatch_sub_agent", "args": {"agentId": "test_agent_writer", "prompt": "尝试删除 test_integration 中的 \"test_perm_write\" 元素。使用 delete_element。"}}
```

### Step 5: 验证 SELF 工具在任何 maxPermission 下可用

即使 test_agent_reader（toolFilter=read_only），也应包含 finish_action。

- **验证方式**: 查看 test_agent_reader 的配置，或通过 dispatch 观察其行为 — Agent 结束响应时会使用 finish_action，说明该工具对其可见

```json
{"tool": "dispatch_sub_agent", "args": {"agentId": "test_agent_reader", "prompt": "回复\"测试完成\"即可。"}}
```

### Step 6: 验证分类映射完整性

检查关键类别中各权限层级的代表性工具：

| 工具 | 期望权限 | 验证方式 |
|------|---------|---------|
| `finish_action` | SELF | dispatch 任意 Agent 观察是否可用 |
| `world_list` | READ | dispatch read_only Agent |
| `write_element` | WRITE | dispatch custom Agent |
| `dispatch_sub_agent` | SYSTEM | 仅 orchestrator 可用 |
| `delete_element` | SYSTEM | read_only Agent 不可见 |
| `gsimap_delete_region` | SYSTEM | read_only Agent 不可见 |

### Step 7: 验证 allowList 机制

test_agent_writer 使用 custom toolFilter + allowList。验证：
- allowList 中的工具（write_element）可调用 ✓（T07 Step 6 已验证）
- allowList 外的 READ 工具（如 world_list）仍可见（因为 maxPermission=READ 隐含所有 READ 工具）
- allowList 外的 WRITE 工具（如 doc_write）不可见

- **工具**: `dispatch_sub_agent`
- **参数**:
  - `agentId`: `test_agent_writer`
  - `prompt`: `尝试在 test_integration 中创建一个新文档，docId 为 test_perm_doc，使用 doc_create 工具。`
- **预期结果**: 失败 — doc_create 权限为 WRITE 但不在 allowList 中（如果 custom filter 的 denylist 模式生效）

> ⚠️ 此测试的具体行为取决于 custom toolFilter 的实现方式。如果 custom 模式默认允许所有匹配 maxPermission 的工具，则此测试可能需要调整。

### Step 8: 验证 gsimap 工具权限

地图工具的权限层级与 GSim Core 工具一致：
- READ: `gsimap_get_hex`、`gsimap_query_radius`、`gsimap_list_regions` 等
- WRITE: `gsimap_generate`、`gsimap_create_region`、`gsimap_init_nation` 等
- SYSTEM: `gsimap_delete_region`

- **工具**: `dispatch_sub_agent`
- **参数**:
  - `agentId`: `test_agent_reader`
  - `prompt`: `查看 test_integration 世界 n0001 节点的 (0,0) hex 信息。使用 gsimap_get_hex。`
- **预期结果**: 成功（gsimap_get_hex 权限为 READ）

```json
{"tool": "dispatch_sub_agent", "args": {"agentId": "test_agent_reader", "prompt": "查看 test_integration 世界 n0001 节点的 (0,0) hex 信息。使用 gsimap_get_hex。"}}
```

### Step 9: 验证 gsimap WRITE 工具被 read_only Agent 拒绝

- **工具**: `dispatch_sub_agent`
- **参数**:
  - `agentId`: `test_agent_reader`
  - `prompt`: `尝试在 test_integration 世界 n0001 节点上重新生成地图（gsimap_generate），radius=20。`
- **预期结果**: 失败 — gsimap_generate 权限为 WRITE，read_only Agent 无法调用

```json
{"tool": "dispatch_sub_agent", "args": {"agentId": "test_agent_reader", "prompt": "尝试在 test_integration 世界 n0001 节点上重新生成地图（gsimap_generate），radius=20。"}}
```

### Step 10: 确认统计覆盖

汇总验证：
- SELF 工具数: 2（finish_action, activate_tool_groups）
- SYSTEM 工具数: >= 9（含 Agent 管理和删除类）
- WRITE 工具数: >= 26
- READ 工具数: >= 43
- **总计工具数**: >= 80

> 实际数量以 `gsim_get_status` 返回的 toolCount 为准。

## 预期通过标准

- [ ] Step 1: maxPermission 限制生效（SELF Agent 只能看到 SELF 工具）
- [ ] Step 2: READ 工具可被 read_only Agent 调用
- [ ] Step 3: WRITE 工具可被 custom Agent 调用
- [ ] Step 4: SYSTEM 工具不可被 low-permission Agent 调用
- [ ] Step 5: SELF 工具始终可见
- [ ] Step 6: 分类映射与文档一致
- [ ] Step 7: allowList 机制生效
- [ ] Step 8: gsimap READ 工具正确分类
- [ ] Step 9: gsimap WRITE 工具的访问控制生效
- [ ] Step 10: 工具总数覆盖完整

## 失败排查提示

| 症状 | 可能原因 | 排查动作 |
|------|---------|---------|
| 权限测试结果与预期不符 | 工具添加后未重新注册 | 检查 GSimulatorApplication 中 ToolRegistry 注册代码 |
| SELF 工具被 read_only 过滤 | ToolFilterEvaluator 逻辑缺陷 | 检查 `allowsWithPermission` 中 SELF bypass 分支 |
| SYSTEM 工具被 read_only Agent 调用 | maxPermission 未正确传递 | 检查 AgentConfig 中 maxPermission 默认值 |

## 扩展测试（可选）

- **E13.1**: 组合 maxPermission=WRITE + allowList 包含 SYSTEM 工具 → 验证 allowList 是否能绕过 maxPermission
- **E13.2**: 新增工具后自动获得正确的默认权限（permission() 默认返回 READ）
- **E13.3**: 通过 update_sub_agent_config 动态修改 maxPermission 后立即生效
