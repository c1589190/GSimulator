# T08: Attachment 独立文件读写

## 前置条件

- T02 通过（test_integration World 存在）
- T03 通过（n0000 → n0001 → n0002 链条存在）
- 当前活跃节点建议设为 n0001

## 背景

GSimulator Phase 2 引入了独立文件 Attachment 机制。大块数据（如地图 MapData、导入文本）以 `nXXXX_{key}.json` 形式存储在节点目录下，节点 JSON 中仅保留 `{"_file": "...", "_type": "external"}` 轻量引用。

## 测试步骤

### Step 1: 确保在 n0001 节点

- **工具**: `node_switch`
- **参数**: `worldId`: `test_integration`, `nodeId`: `n0001`
- **预期结果**: 切换成功

```json
{"tool": "node_switch", "args": {"worldId": "test_integration", "nodeId": "n0001"}}
```

### Step 2: 写入 Attachment

- **工具**: `attachment_write`
- **参数**:
  - `worldId`: `test_integration`
  - `key`: `test_attachment`
  - `data`: `{"name": "测试附件", "items": [1, 2, 3], "nested": {"key": "value"}}`
- **预期结果**: 写入成功，返回确认

```json
{"tool": "attachment_write", "args": {"worldId": "test_integration", "key": "test_attachment", "data": "{\"name\": \"测试附件\", \"items\": [1, 2, 3], \"nested\": {\"key\": \"value\"}}"}}
```

### Step 3: 验证独立文件存在（内部路径验证）

如走 GSimulator 内部路径，可检查文件系统：

```bash
# 验证 n0001_test_attachment.json 文件存在
ls -la data/worlds/test_integration/nodes/n0001_test_attachment.json
```

预期：文件存在且大小 > 0。

### Step 4: 读取 Attachment

- **工具**: `attachment_read`
- **参数**:
  - `worldId`: `test_integration`
  - `key`: `test_attachment`
- **预期结果**: 返回原始 JSON 数据，name="测试附件"，items=[1,2,3]

```json
{"tool": "attachment_read", "args": {"worldId": "test_integration", "key": "test_attachment"}}
```

### Step 5: 写入第二个 Attachment

- **工具**: `attachment_write`
- **参数**:
  - `worldId`: `test_integration`
  - `key`: `test_attachment_2`
  - `data`: `"纯文本内容，非 JSON 对象"`
- **预期结果**: 写入成功

```json
{"tool": "attachment_write", "args": {"worldId": "test_integration", "key": "test_attachment_2", "data": "\"纯文本内容，非 JSON 对象\""}}
```

### Step 6: 列出节点所有 Attachments

- **工具**: 无直接 MCP 工具，需通过 `query_node` 查看 attachments 字段
- **参数**: `worldId`: `test_integration`, `nodeId`: `n0001`
- **预期结果**: attachments 包含 `test_attachment` 和 `test_attachment_2` 两个 key，且它们的值为 `{"_file": "...", "_type": "external"}` 引用格式

```json
{"tool": "query_node", "args": {"worldId": "test_integration", "nodeId": "n0001"}}
```

### Step 7: 后向兼容验证 — 内联数据读取

验证 `attachment_read` 能正确回退读取节点 JSON 中的内联数据（旧格式兼容）。

- **内部路径验证**: 在 n0001.json 的 attachments 字段中手动加入一个内联值 `"inline_key": "inline_value"`，然后用 `attachment_read` 读取，确认返回 "inline_value"

## 预期通过标准

- [ ] Step 1: 节点切换成功
- [ ] Step 2: JSON 对象数据写入成功
- [ ] Step 3: 独立文件 `n0001_test_attachment.json` 存在
- [ ] Step 4: 读取返回数据与写入一致
- [ ] Step 5: 纯文本/非对象数据写入成功
- [ ] Step 6: query_node 显示两个 attachment key
- [ ] Step 7: 内联数据后向兼容读取

## 失败排查提示

| 症状 | 可能原因 | 排查动作 |
|------|---------|---------|
| attachment_write 失败 | data 不是有效 JSON | 用 JSON.parse / ObjectMapper 验证 data 参数 |
| 独立文件不存在 | NodeLoader.saveAttachmentFile 未执行 | 检查文件系统确认路径 |
| attachment_read 返回空 | key 不匹配 | 确认 key 拼写与写入时一致 |
| query_node 中附件显示为 null | NodeLoader.saveAttachment 旧方法问题 | 检查节点 JSON 中 attachments 字段 |

## 扩展测试（可选）

- **E8.1**: 大量数据写入（1MB+ JSON array）验证性能
- **E8.2**: 二进制数据 base64 编码存储
- **E8.3**: 多个节点写同名 key，验证数据隔离（不同 nodeId）
- **E8.4**: 写入后创建子节点，验证子节点附件独立（不继承父节点附件）
