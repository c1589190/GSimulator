# T14: MCP 工具输出分级管控 — 分页与截断

## 前置条件

- T02 通过（test_integration World 存在）
- T04 通过（n0001 有程序化生成的地图）
- MCP Server 已启动并连接（含最新的 Tier 1 + Tier 2 分页功能）

## 概述

本测试验证 MCP 工具输出管控的两个层级：

- **Tier 2（适配器层）**：`_page` / `_pageSize` 通用分页 — 对所有查询工具透明生效
- **Tier 1（工具层）**：`detail` 参数 — 默认摘要模式，`detail=true` 返回完整数据

测试用长文本由 Python 脚本生成（纯数字序列，可控长度），避免依赖外部文件。

---

## Part A — 准备：生成测试数据

### Step A1: 切换到 test_integration world

- **工具**: `gsim_world_switch`
- **参数**: `worldId`: `test_integration`
- **预期结果**: 切换成功

```json
{"tool": "gsim_world_switch", "args": {"worldId": "test_integration"}}
```

### Step A2: 确认当前节点为 n0001

- **工具**: `gsim_node_status`
- **预期结果**: active node 为 n0001

```json
{"tool": "gsim_node_status", "args": {}}
```

### Step A3: 生成超长测试文本（Python）

在终端执行以下命令，生成一个字面数字序列的纯文本（长度精确可控，不含换行）：

```bash
python3 -c "
import json
# 生成 15000 字符的测试文本 (纯数字 0-9 循环)
long_text = ''.join(str(i % 10) for i in range(15000))
with open('/tmp/test_long_text.json', 'w') as f:
    json.dump({'value': long_text}, f)
print(f'Generated {len(long_text)} chars of test text')
"
```

预期输出: `Generated 15000 chars of test text`

### Step A4: 写入超长 WorldInfo 元素

将生成的 15000 字符文本写入 `n0001:narrative` checkpoint。

- **工具**: `gsim_write_element`
- **参数**:
  - `ref`: `n0001:narrative:test_long_narrative`
  - `value`: `<从 /tmp/test_long_text.json 读取>`
  - `type`: `narrative`
- **预期结果**: 写入成功，返回 `(appended)`

> **执行方式**: Agent 应先执行 Step A3 的 bash 命令，然后用 Read 工具读取 `/tmp/test_long_text.json` 的内容，将其值作为 `gsim_write_element` 的 `value` 参数。

### Step A5: 确认元素存在

- **工具**: `gsim_query_element`
- **参数**: `ref`: `n0001:narrative:test_long_narrative`
- **预期结果**: 返回元素，snippet 为完整 15000 字符文本

```json
{"tool": "gsim_query_element", "args": {"ref": "n0001:narrative:test_long_narrative"}}
```

---

## Part B — Tier 2 测试：item 级分页

### Step B1: 写入多个 WorldInfo 元素（≥25 个）

使用 `gsim_write_element` 依次写入至少 25 个元素到 `n0001:map` checkpoint（每个元素都是前 50 字符预览 + 链接到 test_long_narrative），确保 `query_node` 返回 ≥25 个 items。

> **执行方式**: 循环调用 `gsim_write_element` mode=append，ref=`n0001:map:test_item_{i}`，value=`"This is test item number {i}. Full details in test_long_narrative. "` × 25 次。

预期结果：25 个元素全部写入成功。

### Step B2: 使用 query_node 查询 n0001（默认分页）

- **工具**: `gsim_query_node`
- **参数**: `nodeId`: `n0001`
- **预期结果**: 返回 ≤20 个 items（默认每页 20 条），响应包含 `_hasMore: true`、`_totalItems`、`_page: 1`

```json
{"tool": "gsim_query_node", "args": {"nodeId": "n0001"}}
```

**验证点**：
- `itemCount` ≤ 20
- `_hasMore` 为 `true`
- `_totalItems` ≥ 25
- `_page` 为 `1`

### Step B3: 翻到第 2 页

- **工具**: `gsim_query_node`
- **参数**: `nodeId`: `n0001`, `_page`: `2`
- **预期结果**: 返回剩余 items（≥5 个），`_hasMore: false`

```json
{"tool": "gsim_query_node", "args": {"nodeId": "n0001", "_page": 2}}
```

**验证点**：
- `itemCount` ≥ 5
- `_hasMore` 为 `false`

### Step B4: 自定义 pageSize

- **工具**: `gsim_query_node`
- **参数**: `nodeId`: `n0001`, `_pageSize`: `5`
- **预期结果**: 返回 5 个 items，`_totalItems` ≥ 25，`_hasMore: true`

```json
{"tool": "gsim_query_node", "args": {"nodeId": "n0001", "_pageSize": 5}}
```

**验证点**：
- `itemCount` = 5
- `_pageSize` = 5
- `_hasMore` = true
- 翻页：`_page=2` 应返回后续 5 个 items

---

## Part C — Tier 2 测试：单 Item 字符级分页

### Step C1: 查询单个超大元素（默认分页）

- **工具**: `gsim_query_element`
- **参数**: `ref`: `n0001:narrative:test_long_narrative`
- **预期结果**: 返回第 1 页字符（约 4000 字符），末尾附分页提示

```json
{"tool": "gsim_query_element", "args": {"ref": "n0001:narrative:test_long_narrative"}}
```

**验证点**：
- `itemCount` = 1
- snippet 以数字序列开头，结尾包含 `(page 1 of`
- `_hasMore` 为 `true`
- `_totalChars` = 15000
- `_charsPerPage` = 4000（默认 pageSize 20 × 200）

### Step C2: 翻到第 2 页（字符级）

- **工具**: `gsim_query_element`
- **参数**: `ref`: `n0001:narrative:test_long_narrative`, `_page`: `2`
- **预期结果**: 返回 4000-7999 的字符片段

```json
{"tool": "gsim_query_element", "args": {"ref": "n0001:narrative:test_long_narrative", "_page": 2}}
```

**验证点**：
- snippet 以中间数字序列开头
- `_page` = 2
- `_hasMore` = true

### Step C3: 翻到最后一页

- **工具**: `gsim_query_element`
- **参数**: `ref`: `n0001:narrative:test_long_narrative`, `_page`: `4`
- **预期结果**: 返回最后 3000 字符，`_hasMore: false`

```json
{"tool": "gsim_query_element", "args": {"ref": "n0001:narrative:test_long_narrative", "_page": 4}}
```

**验证点**：
- snippet 以数字序列结尾（不包含分页提示，或包含 `page 4 of 4`）
- `_hasMore` = false

### Step C4: 增大字符页尺寸

- **工具**: `gsim_query_element`
- **参数**: `ref`: `n0001:narrative:test_long_narrative`, `_pageSize`: `40`
- **预期结果**: 返回约 8000 字符（40 × 200），只需 2 页即可读完

```json
{"tool": "gsim_query_element", "args": {"ref": "n0001:narrative:test_long_narrative", "_pageSize": 40}}
```

**验证点**：
- snippet 长度 ≈ 8000
- `_charsPerPage` = 8000
- `_totalChars` = 15000

---

## Part D — Tier 1 测试：detail 参数（gsimap 工具）

### Step D1: gsimap_list_regions 默认摘要模式

- **工具**: `gsimap_list_regions`
- **参数**: `worldId`: `test_integration`, `nodeId`: `n0001`
- **预期结果**: 返回区域列表，但不含每个区域的地形构成和邻接信息

```json
{"tool": "gsimap_list_regions", "args": {"worldId": "test_integration", "nodeId": "n0001"}}
```

**验证点**：
- 返回了各区域的名称、tag、hex 数量
- **不包含** `## Terrain Composition` 或 `## Adjacent Regions` 子标题
- 或包含 `(use detail=true for terrain composition and adjacency per region)` 提示

### Step D2: gsimap_list_regions detail 模式

- **工具**: `gsimap_list_regions`
- **参数**: `worldId`: `test_integration`, `nodeId`: `n0001`, `detail`: `true`
- **预期结果**: 返回完整区域信息，含地形构成和邻接

```json
{"tool": "gsimap_list_regions", "args": {"worldId": "test_integration", "nodeId": "n0001", "detail": true}}
```

**验证点**：
- 包含 `**Terrain Composition:**` 段落
- 包含 `**Adjacent Regions:**` 段落

### Step D3: gsimap_get_province 默认摘要模式

- **工具**: `gsimap_get_province`
- **参数**: `worldId`: `test_integration`, `nodeId`: `n0001`, `name`: `test_nation`
- **预期结果**: 返回省份元数据但**不包含逐行 hex 列表**

```json
{"tool": "gsimap_get_province", "args": {"worldId": "test_integration", "nodeId": "n0001", "name": "test_nation"}}
```

**验证点**：
- 包含 tag、color、hex 数量
- 包含 terrain composition 和 adjacency
- **不包含** `### Hex List` 段落
- 或包含 `(hexes omitted — use detail=true for full hex list)` 提示

### Step D4: gsimap_get_province detail 模式

- **工具**: `gsimap_get_province`
- **参数**: `worldId`: `test_integration`, `nodeId`: `n0001`, `name`: `test_nation`, `detail`: `true`
- **预期结果**: 返回完整信息，含逐行 hex 列表

```json
{"tool": "gsimap_get_province", "args": {"worldId": "test_integration", "nodeId": "n0001", "name": "test_nation", "detail": true}}
```

**验证点**：
- 包含 `### Hex List` 段落
- 逐行列出了 hex 坐标和 terrain

### Step D5: gsimap_query_radius 默认摘要模式

- **工具**: `gsimap_query_radius`
- **参数**: `worldId`: `test_integration`, `nodeId`: `n0001`, `q`: `10`, `r`: `0`, `radius`: `10`
- **预期结果**: 返回摘要统计 + 地形分布，但**不包含逐行 hex 列表**

```json
{"tool": "gsimap_query_radius", "args": {"worldId": "test_integration", "nodeId": "n0001", "q": 10, "r": 0, "radius": 10}}
```

**验证点**：
- 包含 `### Summary` 和 `### Terrain Distribution`
- 包含 `(hexes omitted — use detail=true for full hex list)` 提示

---

## Part E — Tier 1 测试：detail 参数（worldinfo 工具）

### Step E1: query_node 默认摘要模式

- **工具**: `gsim_query_node`
- **参数**: `nodeId`: `n0001`, `_pageSize`: `5`
- **预期结果**: 每个元素的 value 被截断到约 200 字符

```json
{"tool": "gsim_query_node", "args": {"nodeId": "n0001", "_pageSize": 5}}
```

**验证点**：
- test_long_narrative 元素的 snippet 以数字开头但不到 15000 字符
- 末尾包含 `(truncated, use detail=true for full content)`

### Step E2: query_node detail 模式

- **工具**: `gsim_query_node`
- **参数**: `nodeId`: `n0001`, `detail`: `true`, `_pageSize`: `5`
- **预期结果**: 每个元素返回完整 value

```json
{"tool": "gsim_query_node", "args": {"nodeId": "n0001", "detail": true, "_pageSize": 5}}
```

**验证点**：
- test_long_narrative 元素的值更长（无 `truncated` 标记，或更长后再配合 Tier 2 字符分页）

### Step E3: query_checkpoint 默认摘要模式

- **工具**: `gsim_query_checkpoint`
- **参数**: `checkpointId`: `narrative`
- **预期结果**: 每个元素 value 截断到 200 字符

```json
{"tool": "gsim_query_checkpoint", "args": {"checkpointId": "narrative"}}
```

**验证点**：
- 包含 `(truncated, use detail=true for full content)` 的截断值

### Step E4: query_by_tag 默认摘要模式

- **工具**: `gsim_query_by_tag`
- **参数**: `tag`: `Nation`, `limit`: `5`
- **预期结果**: 每个元素 value 截断到 200 字符

```json
{"tool": "gsim_query_by_tag", "args": {"tag": "Nation", "limit": 5}}
```

**验证点**：
- 匹配的元素 value 被截断，末尾有截断提示

---

## Part F — 组合测试

### Step F1: gsimap_get_province 与字符分页叠加

当 `detail=true` 且省份 hex 列表极长时（如 ≥200 hex），Tier 2 应对其单 Item 的大 snippet 做字符分页。

- **工具**: `gsimap_get_province`
- **参数**: `worldId`: `test_integration`, `nodeId`: `n0001`, `name`: `test_nation`, `detail`: `true`
- **预期结果**: 如果 hex 列表总长度 >4000 字符，Tier 2 应自动翻页

```json
{"tool": "gsimap_get_province", "args": {"worldId": "test_integration", "nodeId": "n0001", "name": "test_nation", "detail": true}}
```

**验证点**：
- 如果总字符 >4000，`_hasMore` = true，`_page` = 1
- 继续请求 `_page=2` 获得后续 hex

---

## 验收标准

| # | 验证项 | 预期 |
|---|--------|------|
| 1 | query_node 默认返回 ≤20 items | ✅ |
| 2 | query_node _page=2 返回剩余 items | ✅ |
| 3 | query_node _pageSize=5 返回 5 items | ✅ |
| 4 | query_element 单 Item 字符分页 | ✅ _hasMore=true, _totalChars=15000 |
| 5 | query_element _page=2 翻到第二段字符 | ✅ |
| 6 | query_element _pageSize=40 返回 ~8000 字符 | ✅ |
| 7 | gsimap_list_regions 默认无 terrain/adjacency | ✅ |
| 8 | gsimap_list_regions detail=true 有 terrain/adjacency | ✅ |
| 9 | gsimap_get_province 默认无 hex 列表 | ✅ |
| 10 | gsimap_get_province detail=true 有 hex 列表 | ✅ |
| 11 | gsimap_query_radius 默认无 hex 详情 | ✅ |
| 12 | query_node 默认 value 截断 | ✅ |
| 13 | query_node detail=true value 完整 | ✅ |
| 14 | gsimap_get_province detail=true + 字符分页叠加 | ✅ |

---

## 清理

本测试创建的所有数据都在 test_integration world 的 n0001 节点中，不创建独立资源。
在 T13-cleanup 中统一清理 test_integration world。
