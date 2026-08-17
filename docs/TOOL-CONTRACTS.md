# GSimulator 工具契约

> 工具接口定义、权限模型、命名规范与通用模式。
> 系统架构参见 `ARCHITECTURE.md`，数据结构参见 `DATA-MODEL.md`，执行流程参见 `RUNTIME-FLOWS.md`。

---

## 1. AgentTool 接口

```java
public interface AgentTool {
    String name();                           // snake_case, 全局唯一
    String description();                    // LLM-facing, 尽量中文
    ToolResult execute(ToolCall call);       // 同步执行
    Map<String, Object> getParameters();     // JSON Schema (null = 宽 schema)
    Permission permission();                 // SELF < READ < WRITE < SYSTEM
    default boolean requiresWorldId() { return true; }
}
```

所有工具实现此接口，通过 `ToolRegistry` 注册。`getParameters()` 返回 null 表示该工具接受任意参数（如 `finish_action`）。执行结果统一由 `ToolResult` 封装。

## 2. 权限级别

| Permission | 适用工具 | CLI 门禁 | MCP 门禁 |
|------------|---------|---------|---------|
| `SELF` | finish_action, activate_tool_groups | 自动允许 | 自动允许 |
| `READ` | query_\*, list_\*, search_\*, get_\* | 自动允许 | 自动允许 |
| `WRITE` | write_element, create_checkpoint, node_create, gsimap_edge_set | 首次确认 (Y/N/A) | 自动允许 |
| `SYSTEM` | delete_\*, world_delete | 每次确认 (不允许 A) | 需显式审批 |

权限偏序：`SELF < READ < WRITE < SYSTEM`。SubAgent 从 `AgentConfig` 继承 `maxPermission` 上限，超越上限的工具调用被静默拒绝。

## 3. 命名规范

- **内部注册**：短 `snake_case` 名称（`finish_action`, `query_node`, `write_element`）
- **MCP 暴露**：核心工具加 `gsim_` 前缀，地图工具加 `gsimap_` 前缀
  - `gsim_finish_action`, `gsim_query_node`, `gsim_write_element`
  - `gsimap_get_hex`, `gsimap_edge_set`, `gsimap_generate`
- **全局唯一**：工具名在注册表内不重复（MCP 前缀确保外部唯一性）

## 4. 工具组（activate_tool_groups）

LLM 通过 `activate_tool_groups` 在运行时激活工具组，避免 context 被无关工具定义占用。

| 组 Key | 显示名称 | 成员工具 |
|--------|---------|---------|
| `world_info` | WorldInfo 元素读写 | query_node, query_checkpoint, query_keyword, query_element, write_element, create_checkpoint |
| `node_mgmt` | 节点管理 | node_list, node_status, node_create, node_switch, node_goto_parent |
| `import_doc` | 导入文档浏览 | import_document_list, import_document_read, import_document_search |
| `search` | 多源搜索 | wiki_search, mediawiki_search |
| `docs` | 文档管理 | doc_list, doc_read, doc_create, doc_write, doc_search, doc_index, doc_crop |

**默认工具（无需激活）**：finish_action, activate_tool_groups, dispatch_sub_agent, collect_sub_agent_results, list_sub_agent_caches, view_sub_agent_cache, view_sub_agent_output, world_list, world_create, world_switch, compact_cache, doc_list, doc_read, doc_create, doc_write, doc_search, doc_index。

**Gsimap 工具**（`gsimap_\*`）在加载 gsimap 模块后始终可用，无需组激活。

## 5. worldId 要求

`requiresWorldId() = true` 的工具，其 MCP JSON Schema 中自动注入 `worldId` 必填参数。

**需要 worldId**：所有 WorldInfo 工具（query_node, write_element 等）、所有 gsimap 工具（gsimap_get_hex, gsimap_edge_set 等）、所有 node_\* 工具、SubAgent 缓存工具、world_list / world_create / world_switch。

**不需要 worldId**：所有 doc_\* 工具、所有 import_document_\* 工具、LLM provider 工具（list_llm_providers）、Agent 配置工具（agent_config_list, create_sub_agent_config 等）、wiki_search / mediawiki_search。

## 6. 分页模式

每个 MCP 工具自动注入两个参数：
- `_page`：从 1 开始的页码（默认 1）
- `_pageSize`：每页条数（默认 20，最大 100）

两种模式：
- **多条目模式**：pageSize = 每页返回的结果数。如 `query_keyword` 每页返回 20 条匹配。
- **单条目模式**：pageSize × 200 字符为一个逻辑页。如 `doc_read` 的 pageSize=20 表示 4000 字符。

响应始终包含：`_page`, `_pageSize`, `_hasMore: boolean`。

分页与截断限值均可通过 `mcp.response.*` 配置键调整：`default_page_size`（默认 20）、`max_page_size`（默认 100）、`max_json_bytes`（默认 50000）、`snippet_max_chars`（默认 300）、`overflow_staging.enabled`（默认 true，超限结果自动暂存为 TMP 文档并返回 docId）。默认值详见 README `## 配置`。

## 7. 响应格式

**成功**：
```json
{
  "success": true,
  "toolName": "gsim_query_node",
  "items": [...],
  "itemCount": 5,
  "_page": 1, "_pageSize": 20, "_hasMore": false,
  "_context": {"worldId": "default", "nodeId": "n0003", "address": "default/n0003"}
}
```

**错误**：
```json
{
  "success": false,
  "toolName": "gsim_write_element",
  "error": "worldId is required"
}
```

`_context` 是带 worldId 的工具的成功响应必填字段，包含 `{worldId, nodeId, address}`。

## 8. @ 引用系统（resolve_ref）

| 引用格式 | 含义 | 示例 |
|---------|------|------|
| `@world:nodeId:checkpoint:key` | WorldInfo 元素（3 段） | `@world:n0002:characters:曹操` |
| `@world:checkpoint:key` | WorldInfo 元素（2 段，默认活跃节点） | `@world:characters:关羽` |
| `@doc:docId` | Doc 文档 | `@doc:char_guanyu` |
| `@cache:cacheId` | 缓存文本 | `@cache:text_edit_xxx` |
| `@import:documentId` | 导入的外部文档 | `@import:wiki_doc` |

`resolve_ref` 工具（READ 权限）解析任意 @ 引用为完整内容，避免 LLM 复制全文。

## 9. 文本编辑管线（text_edit）

```
Source → resolve_ref (展开 @world:/@doc:/@cache:/@import:)
       → text_edit (select / delete / insert / replace / mask)
       → 新 @cache: 引用
       → write_element (携带 @cache: 引用，自动展开写入)
```

操作类型：select, delete, insert, replace, mask。每种操作指定行范围或文本模式，结果缓存为 `@cache:` 短令牌。

## 10. finish_action 验证规则

`finish_action.message` 经过严格校验，以下内容将被**拒绝**：
1. `[工具调用已执行]` 占位符文本
2. `[工具结果]` / `[TOOL_RESULT]` 伪造标记
3. Fenced JSON tool call（`` ```json {"tool":"..."} ```）
4. 裸 JSON tool call 对象
5. `{key=value}` 伪造工具输出（`MODEL_FAKE_TOOL_RESULT` 检测）

验证失败 → 消息打回 LLM 重写，不消耗额外轮次。

## 11. Gsimap 边工具（专属契约）

地图模块的边/通路工具遵循以下签名：

| 工具 | 功能 | 参数 |
|------|------|------|
| `gsimap_edge_set` | 设置通路边标签 | q1,r1,q2,r2,tag,props? |
| `gsimap_edge_get` | 获取边的全部标签 | q1,r1,q2,r2 |
| `gsimap_edge_remove` | 移除边标签 | q1,r1,q2,r2,tag |
| `gsimap_edge_list` | 筛选列出边 | tag?, hex?, radius? |

详见 `DATA-MODEL.md` — Edge 数据模型章节。

边 key 格式：`"minQ_minR|maxQ_maxR"`（确定性的排序无关键）。所有边操作自动验证六边形邻接性。

## 12. 参考文档

- `ARCHITECTURE.md` — 系统架构总览
- `DATA-MODEL.md` — 工具操作的数据结构定义
- `RUNTIME-FLOWS.md` — 工具执行流程与错误处理
- `CLAUDE.md`（项目根目录）— 工具分类与注册详情
