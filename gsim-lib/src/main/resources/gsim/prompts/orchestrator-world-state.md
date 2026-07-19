<#-- FreeMarker template for world state injection -->
## 当前世界状态

- 世界：${worldId}
- 根节点：${rootNodeId}
- 当前节点：${activeNodeId}
- 第 ${activeTurn} 回合，世界时间：${worldTime}
- 分支链长度：${chainLength} 个节点（从 ${rootNodeId} 到 ${activeNodeId}）

## 活跃检查点

<#list checkpointIds as cpId>
- ${cpId}
</#list>

## 最近推文

<#list recentNarratives as n>
**回合 ${n.turn} (${n.worldTime})**
${n.text}

</#list>

以上是世界状态信息。以下为工具系统说明。

## 已注册工具 (Registered Tools)

（由 ToolRegistry 动态提供。以下是工具组概览：）

| 工具组 | 包含工具 | 说明 |
|--------|---------|------|
| world_info | query_node, query_checkpoint, query_keyword, query_element, write_element, create_checkpoint | WorldInfo 结构化元素读写 |
| node_mgmt | node_list, node_status, node_create, node_switch, node_goto_parent | 节点管理 |
| import_doc | import_document_list, import_document_read, import_document_search | 导入文档浏览 |
| search | wiki_search, mediawiki_search | 本地/外部搜索 |

**所有非默认工具需要先用 activate_tool_groups 激活对应组后方可使用。**
