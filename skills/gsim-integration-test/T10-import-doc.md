# T10: Import 文档浏览

## 前置条件

- GSimulator 正常运行
- `data/worlds/test_integration/import/` 目录存在（或自动创建）
- 可选：手动放入测试用的 txt/md 文件到 import 目录

## 测试步骤

### Step 1: 列出 Import 文档

- **工具**: `import_document_list`
- **参数**: 无（或 `worldId`: `test_integration`）
- **预期结果**: 返回 import 文档列表（可能为空，或包含系统生成的本地文件）

```json
{"tool": "import_document_list", "args": {}}
```

### Step 2: 创建测试导入文件

在 `data/worlds/test_integration/import/` 下手动创建一个测试文件用于后续步骤。

```bash
mkdir -p data/worlds/test_integration/import/
echo "# 测试导入文档

## 简介
这是一个用于测试导入功能的文档。

## 内容
- 第一段：描述测试世界的地理环境
- 第二段：描述主要势力的分布
- 第三段：描述当前的政治局势

## 关键词
测试, 地理, 政治, 势力分布" > data/worlds/test_integration/import/test_import_doc.md
```

- **验证**: 再次调用 `import_document_list`，确认 `test_import_doc.md` 出现在列表中，source=LOCAL_IMPORT

```json
{"tool": "import_document_list", "args": {}}
```

### Step 3: 读取 Import 文档

- **工具**: `import_document_read`
- **参数**: `documentId`: `test_import_doc.md`, `offset`: `0`, `limit`: `100`
- **预期结果**: 返回文档内容，含"测试导入文档"和"地理环境"

```json
{"tool": "import_document_read", "args": {"documentId": "test_import_doc.md", "offset": 0, "limit": 100}}
```

### Step 4: 分段读取 — 第二段

- **工具**: `import_document_read`
- **参数**: `documentId`: `test_import_doc.md`, `offset`: `4`, `limit`: `3`
- **预期结果**: 返回第 5-7 行（offset 为 0-based）

```json
{"tool": "import_document_read", "args": {"documentId": "test_import_doc.md", "offset": 4, "limit": 3}}
```

### Step 5: 全文读取

- **工具**: `import_document_read`
- **参数**: `documentId`: `test_import_doc.md`, `full`: `true`
- **预期结果**: 返回完整文档内容，含 truncated 标记

```json
{"tool": "import_document_read", "args": {"documentId": "test_import_doc.md", "full": true}}
```

### Step 6: 搜索 Import 文档

- **工具**: `import_document_search`
- **参数**: `query`: `地理`, `maxResults`: `5`
- **预期结果**: 返回 test_import_doc.md 及匹配片段的上下文预览

```json
{"tool": "import_document_search", "args": {"query": "地理", "maxResults": 5}}
```

## 预期通过标准

- [ ] Step 1: Import 列表可查询
- [ ] Step 2: 测试文件出现在列表中（source=LOCAL_IMPORT）
- [ ] Step 3: 分段读取正确
- [ ] Step 4: offset/limit 控制正确
- [ ] Step 5: 全文读取返回完整内容
- [ ] Step 6: 关键词搜索命中

## 失败排查提示

| 症状 | 可能原因 | 排查动作 |
|------|---------|---------|
| import_document_list 返回空 | import 目录不存在 | 检查 `data/worlds/test_integration/import/` |
| import_document_read 返回空 | documentId 格式错误 | 使用 import_document_list 返回的精确 documentId |
| import_document_search 无结果 | 关键词不匹配 | 尝试不同的关键词或中英文 |
| 文档不显示为 LOCAL_IMPORT | 文档放在错误目录 | import 目录应在 `data/worlds/{worldId}/import/` |

## 扩展测试（可选）

- **E11.1**: MediaWiki 搜索下载文档验证 WIKI_DOWNLOADED 类型
- **E11.2**: 超过 8000 字符的大文档分段读取验证 nextOffset
- **E11.3**: `import_document_search` 限定 documentId 和 source 过滤
- **E11.4**: 非 txt/md 文件的处理（如 .json）
