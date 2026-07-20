# T09: 通用地址路由

## 前置条件

- T02 通过（test_integration World 存在，含 worldview:背景设定）
- T03 通过（n0000 → n0001 → n0002 链条存在，含 characters:测试角色）
- T04 通过（n0001 有程序化生成的地图）
- T05 通过（n0001 有 test_nation Nation）

## 背景

Phase 3 引入的 `query_address` 是通用地址路由器。它根据地址前缀将请求分发到正确的子系统：
- `n0000:checkpoint:key` → `query_element`（GSim 内部引用）
- `checkpoint:key` → `query_element`（短格式，默认当前节点）
- `plaintext` → `query_by_tag`（按 tag 索引查询）
- `gsimap:region:name` → `gsimap_query_by_address`（地图子系统）
- `gsimap:hex:q_r` → `gsimap_query_by_address`
- `gsimap:city:name` → `gsimap_query_by_address`
- `gsimap:terrain:key` → `gsimap_query_by_address`
- `doc:docId` → `doc_read`（文档引用）
- `cache:id` → `cache_get`（缓存引用）

## 测试步骤

### Step 1: GSim 完整引用格式

- **工具**: `query_address`
- **参数**: `address`: `n0000:worldview:背景设定`
- **预期结果**: 返回世界观背景设定的内容，含"中世纪奇幻"

```json
{"tool": "query_address", "args": {"address": "n0000:worldview:背景设定"}}
```

### Step 2: 短格式（默认当前节点）

- **工具**: `query_address`
- **参数**: `address`: `characters:测试角色`
- **预期结果**: 返回"艾尔文爵士"信息（当前活跃节点为 n0001，其 characters checkpoint 含此元素）

```json
{"tool": "query_address", "args": {"address": "characters:测试角色"}}
```

### Step 3: 按 Tag 查询

`query_address` 对不含冒号的纯文本，路由到 `query_by_tag`。

- **工具**: `query_address`
- **参数**: `address`: `Character`
- **预期结果**: 返回 tag 含 "Character" 的元素，至少包含"艾尔文爵士"

```json
{"tool": "query_address", "args": {"address": "Character"}}
```

### Step 4: gsimap:region 地址

- **工具**: `query_address`
- **参数**: `address`: `gsimap:region:test_nation`
- **预期结果**: 返回 Nation "test_nation" 的地图信息（hex 列表、capital 等）

```json
{"tool": "query_address", "args": {"address": "gsimap:region:test_nation"}}
```

### Step 5: gsimap:hex 地址

- **工具**: `query_address`
- **参数**: `address`: `gsimap:hex:10_0`
- **预期结果**: 返回 hex (10,0) 的详细信息（terrain type、所属 province、color 等）

```json
{"tool": "query_address", "args": {"address": "gsimap:hex:10_0"}}
```

### Step 6: gsimap:city 地址

- **工具**: `query_address`
- **参数**: `address`: `gsimap:city:测试城`
- **预期结果**: 返回城市"测试城"的信息（坐标、所属 province）

```json
{"tool": "query_address", "args": {"address": "gsimap:city:测试城"}}
```

### Step 7: gsimap:terrain 地址

- **工具**: `query_address`
- **参数**: `address`: `gsimap:terrain:forest`
- **预期结果**: 返回 terrain "forest" 的定义信息（已由 T05 Step 11 修改为"密林"）

```json
{"tool": "query_address", "args": {"address": "gsimap:terrain:forest"}}
```

### Step 8: 不存在的地址（负面测试）

- **工具**: `query_address`
- **参数**: `address`: `gsimap:city:不存在之城`
- **预期结果**: 返回失败，错误信息清晰（如"未找到城市"）

```json
{"tool": "query_address", "args": {"address": "gsimap:city:不存在之城"}}
```

## 预期通过标准

- [ ] Step 1: GSim 完整引用路由正确
- [ ] Step 2: 短格式默认当前节点
- [ ] Step 3: 纯文本→byTag 路由正确
- [ ] Step 4: gsimap:region 地址解析成功
- [ ] Step 5: gsimap:hex 地址解析成功
- [ ] Step 6: gsimap:city 地址解析成功
- [ ] Step 7: gsimap:terrain 地址解析成功
- [ ] Step 8: 不存在地址返回明确错误

## 失败排查提示

| 症状 | 可能原因 | 排查动作 |
|------|---------|---------|
| gsimap:* 前缀返回 "worldId required" | gsimap_query_by_address 需要 worldId | 确认 MCP 工具参数配置；内部路径自动从 WorldInformation 获取 |
| 短格式 not found | 当前活跃节点非预期节点 | 先用 node_status 确认 activeNodeId |
| byTag 不返回任何结果 | 标签大小写不匹配 | Tag 系统可能是大小写敏感的，尝试不同大小写 |

## 扩展测试（可选）

- **E10.1**: doc: 前缀的文档地址路由（如 `doc:test_doc_character`）
- **E10.2**: @cache: 前缀引用缓存文本
- **E10.3**: @import: 前缀引用导入文档
- **E10.4**: 循环引用检测（A links B links A）
