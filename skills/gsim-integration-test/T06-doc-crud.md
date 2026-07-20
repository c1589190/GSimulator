# T06: Doc 文档 CRUD

## 前置条件

- T02 通过（test_integration World 存在）

## 测试步骤

### Step 1: 列出已有文档

- **工具**: `doc_list`
- **参数**: `worldId`: `test_integration`
- **预期结果**: 返回文档列表（初始可能为空）

```json
{"tool": "doc_list", "args": {"worldId": "test_integration"}}
```

### Step 2: 创建角色文档

- **工具**: `doc_create`
- **参数**:
  - `worldId`: `test_integration`
  - `docId`: `test_doc_character`
  - `title`: `测试角色：艾尔文`
  - `type`: `character`
  - `content`: `# 艾尔文爵士\n\n## 基本信息\n- 年龄：35\n- 身份：边境领主\n- 领地：北方边境要塞\n\n## 性格特征\n正直而谨慎，对部下宽厚但军纪严明。`
  - `tags`: `角色,测试,Character`
- **预期结果**: 文档创建成功

```json
{"tool": "doc_create", "args": {"worldId": "test_integration", "docId": "test_doc_character", "title": "测试角色：艾尔文", "type": "character", "content": "# 艾尔文爵士\n\n## 基本信息\n- 年龄：35\n- 身份：边境领主\n- 领地：北方边境要塞\n\n## 性格特征\n正直而谨慎，对部下宽厚但军纪严明。", "tags": "角色,测试,Character"}}
```

### Step 3: 创建 Skill 类型文档

- **工具**: `doc_create`
- **参数**:
  - `worldId`: `test_integration`
  - `docId`: `test_doc_skill`
  - `title`: `测试技能：剑术`
  - `type`: `skill`
  - `content`: `# 剑术精通\n\n## 等级\n中级\n\n## 描述\n经过多年边境战斗磨练的剑术技巧。`
  - `tags`: `技能,测试,Combat`
- **预期结果**: 第二个文档创建成功

```json
{"tool": "doc_create", "args": {"worldId": "test_integration", "docId": "test_doc_skill", "title": "测试技能：剑术", "type": "skill", "content": "# 剑术精通\n\n## 等级\n中级\n\n## 描述\n经过多年边境战斗磨练的剑术技巧。", "tags": "技能,测试,Combat"}}
```

### Step 4: 按类型过滤列表

- **工具**: `doc_list`
- **参数**: `worldId`: `test_integration`, `type`: `character`
- **预期结果**: 只返回 test_doc_character

```json
{"tool": "doc_list", "args": {"worldId": "test_integration", "type": "character"}}
```

### Step 5: 读取完整文档

- **工具**: `doc_read`
- **参数**: `worldId`: `test_integration`, `docId`: `test_doc_character`, `limit`: `0`
- **预期结果**: 返回完整文档内容，含"艾尔文爵士"和"边境领主"

```json
{"tool": "doc_read", "args": {"worldId": "test_integration", "docId": "test_doc_character", "limit": 0}}
```

### Step 6: 分段读取文档

- **工具**: `doc_read`
- **参数**: `worldId`: `test_integration`, `docId`: `test_doc_character`, `offset`: `0`, `limit`: `2`
- **预期结果**: 只返回前 2 行

```json
{"tool": "doc_read", "args": {"worldId": "test_integration", "docId": "test_doc_character", "offset": 0, "limit": 2}}
```

### Step 7: 追加文档内容

- **工具**: `doc_write`
- **参数**:
  - `worldId`: `test_integration`
  - `docId`: `test_doc_character`
  - `content`: `\n\n## 近期动态\n接到北方异动的情报，正在加强边境防御。`
  - `mode`: `append`
- **预期结果**: 内容追加成功

```json
{"tool": "doc_write", "args": {"worldId": "test_integration", "docId": "test_doc_character", "content": "\n\n## 近期动态\n接到北方异动的情报，正在加强边境防御。", "mode": "append"}}
```

### Step 8: 搜索文档

- **工具**: `doc_search`
- **参数**: `worldId`: `test_integration`, `query`: `边境`
- **预期结果**: 返回至少 test_doc_character（含"边境"关键词）

```json
{"tool": "doc_search", "args": {"worldId": "test_integration", "query": "边境"}}
```

### Step 9: 按 tag 过滤文档

- **工具**: `doc_list`
- **参数**: `worldId`: `test_integration`, `tag`: `Combat`
- **预期结果**: 只返回 test_doc_skill（tag 含 Combat）

```json
{"tool": "doc_list", "args": {"worldId": "test_integration", "tag": "Combat"}}
```

### Step 10: 删除文档

- **工具**: `doc_delete`
- **参数**: `worldId`: `test_integration`, `docId`: `test_doc_skill`
- **预期结果**: 删除成功

```json
{"tool": "doc_delete", "args": {"worldId": "test_integration", "docId": "test_doc_skill"}}
```

- **验证**: 再次 `doc_list`，确认 test_doc_skill 已移除

```json
{"tool": "doc_list", "args": {"worldId": "test_integration"}}
```

## 预期通过标准

- [ ] Step 1: 初始文档列表可查询
- [ ] Step 2: 角色文档创建成功
- [ ] Step 3: 技能文档创建成功
- [ ] Step 4: type=character 过滤正确
- [ ] Step 5: 全文读取内容正确
- [ ] Step 6: 分段读取可控（offset/limit）
- [ ] Step 7: append 追加内容成功
- [ ] Step 8: 关键词搜索命中
- [ ] Step 9: tag 过滤正确
- [ ] Step 10: 删除成功且验证已移除

## 失败排查提示

| 症状 | 可能原因 | 排查动作 |
|------|---------|---------|
| doc_create 失败 | docId 含非法字符 | docId 仅允许字母数字连字符下划线 |
| doc_search 无结果 | 关键词索引未更新 | doc_search 降级为关键词匹配，确认 query 拼写 |
| doc_read limit=0 不返回全文 | limit 语义差异 | 尝试 limit=-1 或不传 limit |
| doc_delete 后仍出现在列表中 | 缓存未刷新 | 确认文件系统中对应 JSON 已删除 |

## 扩展测试（可选）

- **E6.1**: 创建大量文档（100+），验证列表性能
- **E6.2**: 修改文档 title 和 tags（doc_write mode=replace）
- **E6.3**: 全文替换（mode=replace）后验证旧内容不存在
- **E6.4**: 行范围覆盖（mode=line_range）精确修改
- **E6.5**: doc_index 语义索引建立后搜索精度提升
