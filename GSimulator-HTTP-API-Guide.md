# GSimulator HTTP API — Agent 引导手册

你正在连接一个 **GSimulator** 回合制叙事推演引擎。以下是你需要知道的全部操作方式。

---

## 连接信息

```
Base URL: http://127.0.0.1:8710
```

所有响应均为 `{"success": bool, "data": {...}, "error": {...}}` 格式。

---

## 核心概念

| 概念 | 说明 | 寻址格式 |
|------|------|---------|
| **World** | 独立世界观/剧本 | `default` |
| **Node**（节点） | 分支链上的回合快照，n0000 为根 | `n0000`, `n0002` |
| **Checkpoint**（检查点） | 节点内的分类容器 | `worldview`, `characters`, `factions` |
| **Element**（信息单元） | 键值对，最小数据单元 | `nodeId:checkpointId:key` |
| **Doc**（文档） | 角色设定/Skill/模板等文本资产 | `char_guanyu`, `dual-character-sim` |
| **@cache:id** | 大段文本的短引用，可在写入时自动展开 | `@cache:text_edit_xxx` |

---

## API 速查

### 一、获取文本

**逐级下钻 World 数据：**
```
GET /api/world/{worldId}                               → 世界概览 + 节点链
GET /api/world/{id}/nodes/{nodeId}                     → 节点详情 + 检查点列表
GET /api/world/{id}/nodes/{nodeId}/{checkpointId}      → 元素列表（?truncate=200 控制截断）
GET /api/world/{id}/nodes/{nodeId}/{checkpointId}/{key} → 单个元素全文
```

**一步到位的统一引用（推荐）：**
```
GET /api/ref?ref=@world:n0002:characters:曹操          → World 元素
GET /api/ref?ref=@doc:char_guanyu                      → Doc 文档
GET /api/ref?ref=@cache:text_edit_xxx                  → 缓存文本
GET /api/ref?ref=@import:some_file.txt                 → Import 文档
```

**Doc 文档：**
```
GET /api/docs                          → 列出文档
GET /api/docs/search?q=曹操            → 搜索
GET /api/docs/{docId}?offset=0&limit=200 → 分页读取
```

**跨源搜索：**
```
GET /api/search?q=曹操&scope=all       → world + doc + import 三源搜索
                                       → scope=world|doc|import 可限定来源
```

**缓存文本：**
```
GET /api/caches                        → 列出缓存
GET /api/caches/{cacheId}?offset=&limit= → 分页读取
```

**世界管理：**
```
GET /api/roots                         → 列出所有 World
```

### 二、编辑文本（text_edit 管道）

对任意 `@cache:id` 中的文本进行行级/关键词级编辑，返回新 `@cache:id`：

```
POST /api/caches/{cacheId}/edit
Content-Type: application/json

{
  "select_lines": "1-6, 11-14",      // 保留指定行（可选）
  "delete_lines": "7-10",            // 删除行范围（可选）
  "insert_at": 5,                     // 插入位置 + 文本（可选，配合 insert_text）
  "insert_text": "新内容",
  "replace_spec": "3-4",             // 替换行范围 + 文本（可选，配合 replace_text）
  "replace_text": "替换后的文本",
  "replace_from": "曹操,刘备",        // 关键词替换（两遍防重叠）
  "replace_to": "曹公,刘皇叔",         // 与 replace_from 一一对应
  "mask_kw": "秘密,阴谋",             // 关键词遮蔽为 ***
  "mask_lines_spec": "8-9"            // 整行遮蔽
}

→ 返回: {"newCacheId": "edit_xxx", "ref": "@cache:edit_xxx"}
```

### 三、写回数据

**写入 World Element（支持 @cache: 自动展开）：**
```
POST /api/world/{id}/nodes/{nodeId}/{checkpointId}/{key}
Content-Type: application/json

{"value": "直接文本 或 @cache:edit_xxx", "type": "text", "tags": "tag1,tag2"}
```

**更新 Doc 文档（支持 @cache: 自动展开）：**
```
PATCH /api/docs/{docId}
Content-Type: application/json

{"content": "直接文本 或 @cache:edit_xxx", "title": "新标题"}
```

**创建 Doc 文档：**
```
POST /api/docs
Content-Type: application/json

{"docId": "new_doc", "type": "character", "title": "新角色", "content": "内容", "tags": "tag1,tag2"}
```

---

## 典型工作流

### 读 → 编辑 → 写闭环

```
1. 读取当前状态
   GET /api/world/default/nodes/n0002/characters/曹操
   → 返回 {"value": "曹操，字孟德，沛国谯县人..."}

2. 缓存并编辑
   POST /api/caches/{cacheId}/edit
   {"replace_from": "曹操", "replace_to": "曹公", "select_lines": "0-3"}
   → 返回 {"ref": "@cache:edit_xxx"}

3. 写回 World
   POST /api/world/default/nodes/n0002/characters/曹操
   {"value": "@cache:edit_xxx"}
   → @cache: 自动解析为全文写入
```

### 搜索 → 定位 → 修改

```
1. 全局搜索
   GET /api/search?q=关羽&scope=all
   → 找到 @world:n0002:characters:关羽 和 @doc:char_guanyu

2. 精确读取
   GET /api/ref?ref=@world:n0002:characters:关羽

3. 通过 cache 编辑后写回
   POST /api/caches/{id}/edit  →  PATCH /api/docs/char_guanyu
```

---

## 注意事项

- 中文查询参数需 **URL-encode**（`curl` 会自动处理，代码中注意）
- Checkpoint 不存在时不能用 POST element，需先 `POST /api/world/{id}/nodes/{nid}/{cpId}` 创建
- `value` 和 `content` 字段可直接填 `@cache:id` 引用，系统自动展开为全文
- 所有写操作即时持久化到磁盘，无回滚
