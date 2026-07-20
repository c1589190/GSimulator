# T13: 清理

## 前置条件

- 任意前述测试已执行（本测试项可独立执行以清理残留数据）

## 目标

清理所有测试过程中创建的资源，恢复系统到测试前的干净状态。按依赖顺序从叶子到根依次删除。

## 测试步骤

### Step 1: 清理测试 Agent 配置

删除 T07/T12 创建的测试 Agent 配置。

- **工具**: `delete_agent_config`
- **参数**: `configId`: `test_agent_reader`
- **预期结果**: 删除成功

```json
{"tool": "delete_agent_config", "args": {"configId": "test_agent_reader"}}
```

- **工具**: `delete_agent_config`
- **参数**: `configId`: `test_agent_writer`
- **预期结果**: 删除成功

```json
{"tool": "delete_agent_config", "args": {"configId": "test_agent_writer"}}
```

- **工具**: `delete_agent_config`
- **参数**: `configId`: `test_agent_self`
- **预期结果**: 删除成功（如 T12 创建了此 Agent）

```json
{"tool": "delete_agent_config", "args": {"configId": "test_agent_self"}}
```

### Step 2: 清理测试文档

删除 T06 创建的测试文档。

- **工具**: `doc_delete`
- **参数**: `worldId`: `test_integration`, `docId`: `test_doc_character`
- **预期结果**: 删除成功

```json
{"tool": "doc_delete", "args": {"worldId": "test_integration", "docId": "test_doc_character"}}
```

- **工具**: `doc_delete`
- **参数**: `worldId`: `test_integration`, `docId`: `test_doc_skill`
- **预期结果**: 删除成功（如果尚未在 T06 Step 10 删除）

```json
{"tool": "doc_delete", "args": {"worldId": "test_integration", "docId": "test_doc_skill"}}
```

### Step 3: 清理 SubAgent 缓存

删除 T07 产生的 SubAgent 对话缓存。

- **内部操作**:

```bash
rm -rf data/worlds/test_integration/caches/
```

### Step 4: 清理 Import 测试文件

删除 T10 创建的测试导入文件。

```bash
rm -f data/worlds/test_integration/import/test_import_doc.md
```

### Step 5: 切换到安全的 World

在删除测试 World 前，确保会话不活跃在 test_integration。

- **工具**: `world_switch`
- **参数**: `worldId`: `default`
- **预期结果**: 切换到 default World

```json
{"tool": "world_switch", "args": {"worldId": "default"}}
```

### Step 6: 删除测试 World

- **工具**: `world_delete`
- **参数**: `worldId`: `test_integration`
- **预期结果**: 删除成功，`data/worlds/test_integration/` 目录被递归删除

```json
{"tool": "world_delete", "args": {"worldId": "test_integration"}}
```

### Step 7: 验证清理完成

确认所有测试资源已被移除。

- **工具**: `world_list`
- **参数**: 无
- **预期结果**: test_integration 不在列表中

```json
{"tool": "world_list", "args": {}}
```

- **工具**: `list_agent_config`
- **参数**: 无
- **预期结果**: test_agent_reader、test_agent_writer、test_agent_self 不在列表中

```json
{"tool": "list_agent_config", "args": {}}
```

## 预期通过标准

- [ ] Step 1: 所有测试 Agent 配置已删除
- [ ] Step 2: 测试文档已删除
- [ ] Step 3: 缓存目录已清理
- [ ] Step 4: Import 测试文件已删除
- [ ] Step 5: 切换到 default World
- [ ] Step 6: 测试 World 已删除
- [ ] Step 7: world_list 和 list_agent_config 确认无残留

## 失败排查提示

| 症状 | 可能原因 | 排查动作 |
|------|---------|---------|
| world_delete 失败 | 当前 activeWorld=test_integration | 先切换到其他 World |
| world_delete 失败 | 有 running session 在 test_integration | 结束或切换所有 session |
| delete_agent_config 失败 | Agent 正在运行中 | 等待或 cancel 后重试 |
| file not found 错误 | 某些测试未执行，资源不存在 | 错误可忽略（idempotent 清理） |

## 扩展测试（可选）

- **E13.1**: 自动化清理模式 — 所有步骤不要求确认（用于 CI 环境）
- **E13.2**: 清理部分资源后验证剩余资源不影响系统功能
- **E13.3**: 连续执行 3 次清理，验证幂等性（所有步骤都安全可重复）
