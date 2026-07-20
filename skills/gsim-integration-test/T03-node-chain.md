# T03: Node 链条读写

## 前置条件

- T02 通过（test_integration World 存在，n0000 含世界观设定）
- 当前活跃节点为 n0000

## 测试步骤

### Step 1: 查看当前节点状态

- **工具**: `node_status`
- **参数**: `worldId`: `test_integration`
- **预期结果**: activeNodeId="n0000", turn=1, chain 长度为 1

```json
{"tool": "node_status", "args": {"worldId": "test_integration"}}
```

### Step 2: 列出节点链

- **工具**: `node_list`
- **参数**: `worldId`: `test_integration`, `format`: `tree`
- **预期结果**: 只有 n0000 一个节点

```json
{"tool": "node_list", "args": {"worldId": "test_integration", "format": "tree"}}
```

### Step 3: 创建子节点 n0001

- **工具**: `node_create`
- **参数**:
  - `worldId`: `test_integration`
  - `worldTime`: `纪元元年·春`
  - `label`: `第一回合`
- **预期结果**: 返回成功，新节点 ID 自动分配为 n0001，parentId="n0000"

```json
{"tool": "node_create", "args": {"worldId": "test_integration", "worldTime": "纪元元年·春", "label": "第一回合"}}
```

### Step 4: 在 n0001 写入角色数据

使用 short ref（默认当前节点 n0001）。

- **工具**: `write_element`
- **参数**:
  - `worldId`: `test_integration`
  - `ref`: `characters:测试角色`
  - `value`: `名称：艾尔文爵士\n身份：边境领主\n性格：正直而谨慎\n当前状态：驻守北方边境要塞`
  - `tags`: `角色,Character`
- **预期结果**: 写入成功，自动在 n0001 创建 characters checkpoint

```json
{"tool": "write_element", "args": {"worldId": "test_integration", "ref": "characters:测试角色", "value": "名称：艾尔文爵士\n身份：边境领主\n性格：正直而谨慎\n当前状态：驻守北方边境要塞", "tags": "角色,Character"}}
```

### Step 5: 创建第二个子节点 n0002

- **工具**: `node_create`
- **参数**:
  - `worldId`: `test_integration`
  - `worldTime`: `纪元元年·夏`
  - `label`: `第二回合`
- **预期结果**: n0002 创建成功，parentId="n0001"

```json
{"tool": "node_create", "args": {"worldId": "test_integration", "worldTime": "纪元元年·夏", "label": "第二回合"}}
```

### Step 6: 在 n0002 追加角色数据

使用 mode=append 追加而非覆盖。

- **工具**: `write_element`
- **参数**:
  - `worldId`: `test_integration`
  - `ref`: `characters:测试角色`
  - `value`: `\n近期事件：发现北方有不明军队集结的迹象。`
  - `mode`: `append`
- **预期结果**: 追加成功，角色元素现在含两段内容

```json
{"tool": "write_element", "args": {"worldId": "test_integration", "ref": "characters:测试角色", "value": "\n近期事件：发现北方有不明军队集结的迹象。", "mode": "append"}}
```

### Step 7: 跨节点查询 checkpoint 历史

验证 parent 链上的元素继承。

- **工具**: `query_checkpoint`
- **参数**:
  - `worldId`: `test_integration`
  - `checkpointId`: `characters`
- **预期结果**: 返回 n0001 和 n0002 两个版本的角色数据，n0002 版本含"不明军队"

```json
{"tool": "query_checkpoint", "args": {"worldId": "test_integration", "checkpointId": "characters"}}
```

### Step 8: 节点回退

- **工具**: `node_goto_parent`
- **参数**: `worldId`: `test_integration`
- **预期结果**: 当前节点回到 n0001（n0002 的父节点）

```json
{"tool": "node_goto_parent", "args": {"worldId": "test_integration"}}
```

- **验证**: 再次调用 `node_status`，确认 activeNodeId="n0001"

```json
{"tool": "node_status", "args": {"worldId": "test_integration"}}
```

## 预期通过标准

- [ ] Step 1: n0000 节点状态正确
- [ ] Step 2: 初始链只有 1 个节点
- [ ] Step 3: n0001 创建成功
- [ ] Step 4: 角色数据写入成功
- [ ] Step 5: n0002 创建成功
- [ ] Step 6: append 模式追加成功
- [ ] Step 7: query_checkpoint 返回跨节点历史
- [ ] Step 8: node_goto_parent 回到 n0001

## 失败排查提示

| 症状 | 可能原因 | 排查动作 |
|------|---------|---------|
| node_create 返回成功但链不变 | Session 未同步 | 检查 node_list 确认新节点是否在链上 |
| query_checkpoint 只返回一个版本 | checkpoint 在不同节点间未继承 | 检查 parentId 链是否正确，用 query_node 核实 |
| append 覆盖而非追加 | mode 参数未正确传递 | 确认 "mode": "append" 拼写正确 |
| node_goto_parent 回到 n0000 | parentId 映射错误 | 检查 n0001.json 中 parentId 字段 |

## 扩展测试（可选）

- **E3.1**: 创建分支（从 n0001 再次 node_create，验证 n0001 有两个子节点）
- **E3.2**: node_switch 直接跳到链内任意节点
- **E3.3**: 深层链（连续创建 10 个节点）性能测试
- **E3.4**: query_keyword 全文搜索"艾尔文"跨节点命中
