# T01: MCP 连接与工具清单验证

## 前置条件

- GSimulator 已启动（如走 MCP 路径：gsimap MCP Server 已连接）
- 如走内部路径：ToolRegistry 已初始化

## 测试步骤

### Step 1: 获取服务状态

验证 MCP Server 或内部运行时可达并返回基本信息。

- **内部路径工具**: 无直接等价工具，通过 `world_list` 间接验证
- **MCP 路径工具**: `mcp__gsimap__gsim_get_status`
- **参数**: 无
- **预期结果**: 返回 version、directories、worldCount、toolCount，toolCount > 0

```json
{"tool": "gsim_get_status", "args": {}}
```

### Step 2: 工具清单完整性检查

确认所有核心工具类别的代表性工具均已注册。

- **内部路径**: 调用 `activate_tool_groups` 激活全部工具组，LLM 从 system prompt 获取工具列表
- **MCP 路径**: 遍历 MCP 工具列表，检查以下关键工具是否存在

**必须存在的核心工具（内部路径名称）**:

| 类别 | 必须存在 | 权限级别 |
|------|---------|---------|
| World | `world_create`, `world_list` | WRITE, READ |
| Node | `node_create`, `node_list`, `node_status` | WRITE, READ, READ |
| WorldInfo | `write_element`, `query_element`, `query_address` | WRITE, READ, READ |
| Doc | `doc_create`, `doc_read`, `doc_search`, `doc_delete` | WRITE, READ, READ, SYSTEM |
| Agent | `dispatch_sub_agent`, `create_sub_agent_config`, `list_agent_config`, `delete_agent_config` | SYSTEM, WRITE, READ, SYSTEM |
| GSimap | `gsimap_generate`, `gsimap_get_hex`, `gsimap_create_region` | WRITE, READ, WRITE |
| Import | `import_document_list`, `import_document_read` | READ, READ |
| Cache | `list_sub_agent_caches`, `view_sub_agent_cache` | READ, READ |
| Flow | `finish_action`, `activate_tool_groups` | SELF, SELF |

**MCP 路径额外工具**: `gsim_mediawiki_search`, `gsim_resolve_ref`, `gsim_cache_edit`

### Step 3: LLM Provider 检查

确认至少有一个 LLM provider 可用。

- **内部路径工具**: `list_llm_providers`
- **MCP 路径工具**: `mcp__gsimap__gsim_list_llm_providers`
- **参数**: 无
- **预期结果**: 返回至少 1 个 provider，包含 id、model、baseUrl

```json
{"tool": "list_llm_providers", "args": {}}
```

### Step 4: Agent 配置检查

确认至少存在 orchestrator 配置。

- **内部路径工具**: `list_agent_config`
- **MCP 路径工具**: `mcp__gsimap__gsim_agent_config_list`
- **参数**: 无
- **预期结果**: 列表中包含 agentId="orchestrator" 的配置，maxToolRounds >= 16

```json
{"tool": "list_agent_config", "args": {}}
```

## 预期通过标准

- [ ] Step 1: 返回 status，toolCount >= 20
- [ ] Step 2: 所有 10 个类别的代表性工具均可发现
- [ ] Step 3: 至少 1 个 LLM provider，含有效 model 名
- [ ] Step 4: orchestrator 配置存在且可读

## 失败排查提示

| 症状 | 可能原因 | 排查动作 |
|------|---------|---------|
| `gsim_get_status` 无响应 | GSimulator 未启动 | 检查 Java 进程 `jps -l \| grep GSimulator` |
| toolCount < 20 | ToolRegistry 初始化不完整 | 检查 `data/` 目录完整性，删除后重新 bootstrap |
| `list_llm_providers` 返回空 | `llms.json` 未配置或损坏 | 检查 `data/llms.json`，确认 JSON 有效 |
| `list_agent_config` 缺少 orchestrator | `data/agents/` 目录缺失 | 删除 `data/agents/` 后重新启动以触发自动初始化 |

## 扩展测试（可选）

- **E1.1**: 多次调用 `gsim_get_status` 验证 worldCount 一致性（无内存泄漏）
- **E1.2**: 检查所有工具的 `permission()` 返回值非 null（验证 Phase 1 权限系统完整性）
- **E1.3**: 验证 SYSTEM 级别工具数量 >= 8（Agent 管理 + 删除类工具）
- **E1.4**: 验证 SELF 级别工具至少包含 `finish_action`、`activate_tool_groups`
