# T02: World 创建与基础操作

## 前置条件

- T01 通过（服务可用）
- 不存在名为 `test_integration` 的 World（如有残留，先执行 T13 清理）

## 测试步骤

### Step 1: 列出已有 World

记录测试前的 World 列表，用于后续验证和恢复。

- **工具**: `world_list`
- **参数**: 无
- **预期结果**: 返回 World 列表（至少含 default），记录数量 N

```json
{"tool": "world_list", "args": {}}
```

### Step 2: 创建测试 World

- **工具**: `world_create`
- **参数**:
  - `worldId`: `test_integration`
  - `name`: `集成测试世界`
- **预期结果**: 返回成功，worldId="test_integration"

```json
{"tool": "world_create", "args": {"worldId": "test_integration", "name": "集成测试世界"}}
```

### Step 3: 验证 World 已创建

- **工具**: `world_list`
- **参数**: 无
- **预期结果**: 列表包含 test_integration，World 总数 = N+1
- **额外验证**（内部路径）: 检查 `data/worlds/test_integration/` 目录存在，含 `nodes/n0000.json`、`world.json`、`active.json`

```json
{"tool": "world_list", "args": {}}
```

### Step 4: 查看根节点 n0000

- **工具**: `query_node`
- **参数**:
  - `worldId`: `test_integration`
  - `nodeId`: `n0000`
- **预期结果**: 返回 n0000 节点信息，status="active"，turn=1，checkpoints 至少含空的 worldview

```json
{"tool": "query_node", "args": {"worldId": "test_integration", "nodeId": "n0000"}}
```

### Step 5: 写入世界观设定

- **工具**: `write_element`
- **参数**:
  - `worldId`: `test_integration`
  - `ref`: `worldview:背景设定`
  - `value`: `这是一个用于集成测试的架空世界。世界处于中世纪奇幻时代，大陆上有多个王国并存。`
  - `tags`: `测试,世界观`
- **预期结果**: 返回成功，确认写入

```json
{"tool": "write_element", "args": {"worldId": "test_integration", "ref": "worldview:背景设定", "value": "这是一个用于集成测试的架空世界。世界处于中世纪奇幻时代，大陆上有多个王国并存。", "tags": "测试,世界观"}}
```

### Step 6: 回读验证写入

- **工具**: `query_element`
- **参数**:
  - `worldId`: `test_integration`
  - `ref`: `n0000:worldview:背景设定`
- **预期结果**: 返回值包含 "集成测试" 和 "中世纪奇幻"

```json
{"tool": "query_element", "args": {"worldId": "test_integration", "ref": "n0000:worldview:背景设定"}}
```

## 预期通过标准

- [ ] Step 1: world_list 正常返回
- [ ] Step 2: test_integration 创建成功
- [ ] Step 3: 新 World 出现在列表中
- [ ] Step 4: n0000 节点可查询
- [ ] Step 5: 背景设定写入成功
- [ ] Step 6: 回读内容一致（含"集成测试"和"中世纪奇幻"）

## 失败排查提示

| 症状 | 可能原因 | 排查动作 |
|------|---------|---------|
| world_create 失败 | worldId 含非法字符 | worldId 仅允许 `[a-zA-Z0-9_-]` |
| world_create 失败 | test_integration 已存在 | 先执行 `world_delete` 或 T14 清理 |
| query_node 返回空 | n0000 未正确初始化 | 检查 `data/worlds/test_integration/nodes/n0000.json` |
| write_element 失败 | 权限不足 | 确认 orchestrator 的 maxPermission >= WRITE |

## 扩展测试（可选）

- **E2.1**: 创建多个 World 后 world_list 排序正确
- **E2.2**: worldId 含下划线和连字符的边界测试（如 `test_world-v2`）
- **E2.3**: 重复创建同名 worldId，验证错误信息清晰
- **E2.4**: world_switch 切换后，query_node 默认使用当前活跃 world
