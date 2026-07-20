---
name: GSimulator 集成验收测试
description: 全流程集成测试：覆盖 MCP 连接、World/Node/Map/Doc/Agent/Skill/Attachment/Import/Cache/权限矩阵 14 个测试项，支持清单/交互/自动三种执行模式
category: testing
tags: [integration-test, acceptance, regression, mcp, full-coverage]
---

# GSimulator 集成验收测试

## 概述

本 Skill 对 GSimulator 进行端到端集成验收测试，覆盖所有核心子系统及其交互边界。测试用例定义与执行模式分离——同一套用例可通过三种模式执行。

### 测试覆盖矩阵

| # | 测试项 | 覆盖子系统 | 依赖 |
|---|--------|-----------|------|
| T01 | MCP 连接与工具清单验证 | MCP Server, ToolRegistry | — |
| T02 | World 创建与基础操作 | worldinfo, root | — |
| T03 | Node 链条读写 | worldinfo, session | T02 |
| T04 | 地图程序化生成与查询 | gsimap (generate/query) | T02, T03 |
| T05 | 地图修改操作 | gsimap (mutate) | T04 |
| T06 | Doc 文档 CRUD | doc | T02 |
| T07 | 子 Agent 创建/派发/权限边界 | agent, tool | — |
| T08 | Attachment 独立文件读写 | worldinfo (NodeLoader) | T02, T03 |
| T09 | 通用地址路由 | query_address, gsimap | T02, T03 |
| T10 | Import 文档浏览 | importing | — |
| T11 | 缓存与压缩 | cache, compact | T07 |
| T12 | 权限矩阵全覆盖 | AgentTool.Permission | T07 |
| T13 | 清理 | 全部 | 全部 |

---

## 执行模式

### Mode 1 — Checklist（清单模式）

Agent 逐项列出每步的工具调用和参数，**不实际执行**，等用户逐条确认。适用于：
- 手工验收：用户逐步批准每个操作
- 学习/培训：了解每个工具的参数和行为
- 调试：选择性执行特定步骤

使用方式：加载 Skill 后说 "用 checklist 模式跑 T03"

```text
## Mode 1 行为规则
1. 对每一步，先打印: `🔲 [Step N/M] <工具名> — <一句话描述>`
2. 打印完整的 tool call JSON（tool + args）
3. 等待用户回复 "执行" / "跳过" / "查看参数说明" / "终止"
4. 执行后打印 ✅/❌ + 实际结果摘要
5. 全部步骤执行完后打印本测试项的通过率
```

### Mode 2 — Interactive（交互模式）

Agent 按阶段批量执行，每完成一个测试文件的全部步骤后暂停，展示摘要，询问 "继续下一项？"。适用于：
- 整体验收：逐项确认系统状态
- 问题定位：在某项失败时暂停深入排查

使用方式：加载 Skill 后说 "用 interactive 模式跑全部"

```text
## Mode 2 行为规则
1. 打印: `▶ 开始 T{N}: <标题>`
2. 自动执行该测试项的所有步骤
3. 每步打印 ✅/❌ + 简短结果
4. 全部步骤完成后打印该测试项的汇总: `T{N} 结果: X/Y 通过`
5. 暂停，打印: `继续下一项？(Y/N/重跑当前/深入排查)`
6. 用户确认后进入下一测试项
```

### Mode 3 — Automated（自动模式）

Agent 一口气跑完指定范围的全部测试，最后输出汇总报告。适用于：
- CI/回归测试
- 重构后的全面验证
- 发版前最终检查

使用方式：加载 Skill 后说 "用 automated 模式跑 T01-T14"

```text
## Mode 3 行为规则
1. 打印: `🚀 GSimulator 集成测试 — Automated Mode — {时间戳}`
2. 按依赖顺序自动执行全部测试
3. 每项只打印: `✅ T{N} X/Y` 或 `❌ T{N} X/Y`
4. 单项失败不终止，继续下一项
5. 全部完成后打印汇总报告:

   | 测试项 | 通过/总数 | 耗时 | 结果 |
   |--------|----------|------|------|
   | T01    | 3/3      | 0.2s | ✅   |
   | T02    | 5/5      | 1.1s | ✅   |
   | ...    | ...      | ...  | ...  |

6. 打印总通过率: `总计: XX/YY 步骤通过 (ZZ%)`
7. 如有失败，列出失败详情（测试项/步骤/工具/错误消息）
```

---

## 双路径执行

本 Skill 支持两条执行路径：

### 路径 A：GSimulator 内部 Agent（Orchestrator 加载 Skill）

工具名为 GSimulator ToolRegistry 注册的原生名称，如 `world_create`、`gsimap_generate` 等。
这是默认路径，各 T*.md 中给出的工具调用示例均使用此路径。

### 路径 B：Claude Code 通过 MCP 远程调用

通过 gsimap MCP Server 暴露的工具，名称带服务前缀。调用时使用：
- GSim Core 工具: `mcp__gsimap__gsim_<工具名>`（如 `mcp__gsimap__gsim_world_create`）
- GSimap 地图工具: `mcp__gsimap__gsimap_<工具名>`（如 `mcp__gsimap__gsimap_generate`）

**MCP 工具名映射规则**：
- `world_create` → `mcp__gsimap__gsim_world_create`
- `node_create` → `mcp__gsimap__gsim_node_create`（需先激活 `node_mgmt` 工具组）
- `write_element` → `mcp__gsimap__gsim_write_element`（需先激活 `world_info` 工具组）
- `query_element` → `mcp__gsimap__gsim_query_element`
- `gsimap_*` → `mcp__gsimap__gsimap_*`（地图工具一一对应）
- Agent 管理工具 → `mcp__gsimap__gsim_agent_config_list`, `mcp__gsimap__gsim_agent_config_delete`, `mcp__gsimap__gsim_create_sub_agent_config` 等
- Doc 工具 → `mcp__gsimap__gsim_doc_*` 系列（含 `gsim_doc_delete`）
- Import 工具 → `mcp__gsimap__gsim_import_document_*` 系列
- Cache 工具 → `mcp__gsimap__gsim_cache_*` 系列

**注意**：MCP 路径在 `--no-cli` 模式下启动完整应用，包含全部工具（当前 ~67 个）。执行前先调用 `mcp__gsimap__gsim_get_status` 确认 MCP 版本和可用工具数。

---

## 前置条件

1. GSimulator 已构建并可运行（`mvn package -DskipTests` 成功）
2. `data/` 目录存在且已完成 bootstrap 初始化
3. LLM provider 已配置（`data/llms.json` 含有效 API key）
4. 如走 MCP 路径：gsimap MCP Server 已配置并连接到当前 Claude Code 会话

在开始测试前，Agent 应先执行环境检查：

```text
## 环境检查（任何模式的第一步）
1. 检查 data/ 目录存在
2. 检查 data/llms.json 可读
3. 检查 data/agents/ 目录存在（至少含 orchestrator.json）
4. 如 MCP 路径：调用 gsim_get_status 确认 server 可用
5. 记录当前活跃 world（如有），测试完成后恢复
```

---

## 命名约定（防冲突）

所有测试创建的资源使用统一前缀 `test_`，避免与用户真实数据冲突：

- World ID: `test_integration`
- Agent Config ID: `test_agent_reader`、`test_agent_writer`
- Doc ID: `test_doc_character`、`test_doc_skill`
- Node: 使用 `node_create` 自动生成的编号（从 n0001 开始）
- Region: `test_region`
- Province/Nation: `test_nation`
- Attachment Key: `test_attachment`

---

## 测试项索引

| 文件 | 标题 | 步骤数 | 预计耗时 |
|------|------|--------|---------|
| [T01-mcp-connection.md](T01-mcp-connection.md) | MCP 连接与工具清单 | 4 | <1min |
| [T02-world-create.md](T02-world-create.md) | World 创建与基础操作 | 6 | <1min |
| [T03-node-chain.md](T03-node-chain.md) | Node 链条读写 | 8 | 2min |
| [T04-map-generate.md](T04-map-generate.md) | 地图程序化生成与查询 | 10 | 3min |
| [T05-map-modify.md](T05-map-modify.md) | 地图修改操作 | 12 | 5min |
| [T06-doc-crud.md](T06-doc-crud.md) | Doc 文档 CRUD | 10 | 2min |
| [T07-subagent-perm.md](T07-subagent-perm.md) | 子 Agent 创建/派发/权限 | 12 | 5min |
| [T08-attachment.md](T08-attachment.md) | Attachment 独立文件读写 | 7 | 1min |
| [T09-address-routing.md](T09-address-routing.md) | 通用地址路由 | 8 | 2min |
| [T10-import-doc.md](T10-import-doc.md) | Import 文档浏览 | 6 | 1min |
| [T11-cache-compact.md](T11-cache-compact.md) | 缓存与压缩 | 6 | 2min |
| [T12-permission-matrix.md](T12-permission-matrix.md) | 权限矩阵全覆盖 | 10 | 5min |
| [T13-cleanup.md](T13-cleanup.md) | 清理 | 7 | 1min |

**总计: 107 步骤, 预计 31 分钟**（不含 LLM 推理耗时）
