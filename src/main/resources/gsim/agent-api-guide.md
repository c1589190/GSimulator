# GSimulator HTTP API — Agent 引导手册

> 你是一个外部 AI Agent，连接到一个回合制叙事推演引擎。  
> 核心原则：**用 @ 引用替代复制全文，用 text_edit 编辑管道替代来回搬运大段文本。**

---

## 连接

```
Base:   http://127.0.0.1:8710
Help:   GET /api/help        (本手册)
Status: GET /api/status
```

所有响应格式：`{"success": bool, "data": {...}, "error": {...}}`

中文参数需 **URL-encode**（curl 自动处理，代码中手动做）。

---

## @ 引用系统（省 Token 核心）

**永远不要复制全文，用 @ 引用。**

| 引用格式 | 含义 | 示例 |
|---------|------|------|
| `@world:n0002:characters:曹操` | World 元素（3段：节点:检查点:key） | 角色设定 |
| `@world:characters:曹操` | 同上（2段，默认活跃节点） | 同上 |
| `@doc:char_guanyu` | Doc 文档 | 角色/技能/模板 |
| `@cache:text_edit_xxx` | 缓存文本（大段输出短引用） | 编辑中间结果 |
| `@import:wiki_doc.txt` | 导入的外部文档 | 参考资料 |

**读取用 GET /api/ref**：
```
GET /api/ref?ref=@world:n0002:characters:曹操
→ {"source":"world", "title":"曹操 @n0002", "content":"全文..."}
```

**写入时直接用 @ 引用**（自动展开为全文）：
```json
POST /api/world/default/nodes/n0002/outcomes/result
{"value": "@cache:edit_20260704_xxx", "type": "narrative"}
```

**搜索用 GET /api/search**：
```
GET /api/search?q=曹操&scope=all   → world + doc + import 三源
GET /api/search?q=曹操&scope=world → 仅 world
```

---

## 文本编辑管道（不要复制粘贴）

### 流程

```
读文本 → 存 @cache: → 编辑 → 新 @cache: → 写入目标
```

### 1. 获取文本并存为 @cache:

```
GET /api/ref?ref=@world:n0002:characters:曹操
→ 内容超过 200 字符时自动返回 @cache:id

或手动：
POST /api/caches/{cacheId}/edit
{"select_lines": "0-999"}    # 保留全部行
→ {"newCacheId": "edit_xxx", "ref": "@cache:edit_xxx"}
```

### 2. 编辑文本（支持链式操作）

```
POST /api/caches/{cacheId}/edit

操作（按 select→delete→insert→replace_lines→replace_kw→mask 顺序执行）：

select_lines:    "1-6, 11-14"     # 保留指定行
delete_lines:    "7-10"           # 删除行
insert_at:       5                 # 在第5行前插入
insert_text:     "新内容"          # 配合 insert_at
replace_spec:    "3-4"            # 替换行范围
replace_text:    "替换后文本"      # 配合 replace_spec
replace_from:    "曹操,刘备"       # 关键词替换（两遍防重叠）
replace_to:      "曹公,刘皇叔"     # 一一对应
mask_kw:         "秘密,阴谋"       # 遮蔽为 ***
mask_lines_spec: "8-9"            # 整行遮蔽

→ 返回 {"newCacheId": "edit_xxx", "ref": "@cache:edit_xxx"}
```

### 3. 写回到任意目标

```
# 写入 World
POST /api/world/{id}/nodes/{nid}/{cpId}/{key}
{"value": "@cache:edit_xxx", "type": "text", "tags": "tag1,tag2"}

# 更新 Doc
PATCH /api/docs/{docId}
{"content": "@cache:edit_xxx"}

# 创建 Doc
POST /api/docs
{"docId": "new_doc", "type": "character", "title": "新角色", "content": "@cache:edit_xxx"}
```

---

## 数据读取速查

### World（世界数据）

```
逐级下钻：
GET /api/roots                                    → 世界列表
GET /api/world/{id}                               → 概览 + 节点链
GET /api/world/{id}/nodes/{nid}                   → 检查点列表
GET /api/world/{id}/nodes/{nid}/{cpId}?truncate=200 → 元素截断预览
GET /api/world/{id}/nodes/{nid}/{cpId}/{key}       → 单个元素全文

一步到位（推荐）：
GET /api/ref?ref=@world:n0002:characters:曹操
GET /api/search?q=曹操&scope=world
```

### Doc（文档/技能/角色/子目录）

```
GET /api/docs                          → 列出所有
GET /api/docs/search?q=关键词          → 关键词搜索
GET /api/docs/{docId}                  → 分页读取
GET /api/docs/{sub/dir/docId}          → 子目录读取
GET /api/ref?ref=@doc:sub/dir/docId    → 一步读取

# 创建时自动路由到 World（省去两步操作）
POST /api/docs
Content-Type: application/json
X-GSim-World-Ref: @world:arknights:n0000:characters:新角色

{"docId": "sub/victoria/duke", "content": "..."}
→ 自动在指定 World checkpoint 创建 route_to_doc 元素
```

### Cache（编辑中间文本）

```
GET /api/caches                          → 列出
GET /api/caches/{id}                     → 读取
GET /api/caches/{id}?offset=10&limit=50  → 分页读取
POST /api/caches/{id}/edit               → 编辑
```

---

## 典型工作流

### 工作流 A：读 → 编辑 → 写回（最省 Token）

```
1. GET /api/ref?ref=@world:n0002:outcomes:summary
   → 内容长，自动带 @cache:id

2. POST /api/caches/{cacheId}/edit
   {"replace_from": "曹操", "replace_to": "曹公",
    "insert_at": 3, "insert_text": "新增段落..."}
   → {"ref": "@cache:edit_new"}

3. POST /api/world/default/nodes/n0003/outcomes/updated
   {"value": "@cache:edit_new"}
```

### 工作流 B：跨源搜索 → 组合信息

```
1. GET /api/search?q=关羽&scope=all
   → [{source:"world", ref:"@world:n0002:characters:关羽"},
       {source:"doc", ref:"@doc:char_guanyu"}]

2. GET /api/ref?ref=@world:n0002:characters:关羽   → 当前状态
3. GET /api/ref?ref=@doc:char_guanyu              → 角色设定
  
4. 组合后通过 cache edit → @cache:merged → write_element
```

### 工作流 C：route_to_doc（数据路由）

```
1. POST /api/world/{id}/nodes/{nid}/characters/赵云
   {"value": "@doc:char_zhaoyun", "type": "route_to_doc"}

2. GET /api/world/{id}/nodes/{nid}/characters/赵云
   → renderedContent 自动注入 Doc 全文
   → 修改 Doc 则所有路由自动更新
```

---

## 省 Token 最佳实践

| 做法 | 效果 |
|------|------|
| 读用 GET /api/ref 不用逐级下钻 | 一次请求，全文到手 |
| 查用 GET /api/search 不用穷举 | 精准定位，不翻页 |
| 编辑走 cache 管道 | 不复制全文，只传操作指令 |
| 写入填 @cache:id | body 只有几十字节，自动展开 |
| 大文本先存为 Doc | 用 route_to_doc 一次引用多处复用 |
| checkpoint ?truncate=200 | 预览阶段不拉全文 |
| 中文搜索 scope=world | 限定范围减少无关结果 |
