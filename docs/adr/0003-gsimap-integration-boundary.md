# ADR 0003: Gsimap 集成边界

**Status:** Accepted

## Context

`gsimap` 模块拥有完整的技术栈：

- **数据模型** — `MapData` record、`HexCell`、`Province`、`TerrainType`、`PathwayGroup`、`CompressedRegion`、`edges` 等独立于 WorldInfo 体系的地图专有类型。
- **HTTP 服务器** — `GsimapHttpServer` 监听端口 8711，使用 `com.sun.net.httpserver.HttpServer`。
- **前端** — Canvas 驱动的浏览器编辑器，14 个原生 Vanilla JS 模块，与 gsim-lib 的 WebUI（HTMX + Thymeleaf）技术栈完全不同。
- **持久化** — `MapStore` + `MapResolver` 差异链（diff-chain）重建机制，独立于 gsim-lib 的 WorldInfo JSON 文件体系。
- **MCP 工具** — 20 个以 `gsimap_` 为前缀的工具。

核心问题在于：gsimap 应该在多大程度上复用 gsim-lib 的基础设施？过度复用会导致 gsim-lib 被迫理解地图概念；过度隔离则导致重复造轮子。

`ARCHITECTURE.md` 中定义的"交互层抽象"和"工具注册制"原则为决策提供了指导框架。

## Decision

Gsimap 仅复用 gsim-lib 的工具系统接口和请求上下文，其余全部独立管理：

| 维度 | 复用 gsim-lib | 独立管理 |
|------|--------------|---------|
| 工具接口 | `AgentTool`、`ToolRegistry`、`ToolCall`、`ToolResult` | — |
| 请求上下文 | `GsimRequestContext`（worldId 传播） | — |
| 持久化 | — | `MapStore` + `MapResolver`（JSON 差异链） |
| HTTP | — | `GsimapHttpServer`（独立端口 8711，`com.sun.net.httpserver.HttpServer`） |
| 前端 | — | Canvas + Vanilla JS（14 模块） |
| MCP 注册 | 通过 `Main.java` 中显式调用 `GsimapToolRegistrar.registerAll()` | 不自动发现 |
| MCP 命名 | — | `gsimap_` 前缀，与 gsim-lib 的 `gsim_` 命名空间隔离 |

关键约束：**gsim-lib 对 gsimap 的 import 数量为零**。gsim-lib 不依赖 gsimap 的任何类，不存在反向依赖。`gsim-app` 模块是整个系统中唯一知晓两个模块存在的集成点。

## Consequences

1. **干净的架构边界** — gsim-lib 完全不了解十六进制地图的存在，也永远不会。模块间的契约仅通过 `AgentTool` / `ToolRegistry` 等抽象接口传递，没有隐式耦合。
2. **独立开发节奏** — gsimap 可以由不同的开发者或团队独立迭代，数据模型的重构、前端框架的更换、持久化策略的调整都不会影响核心引擎。
3. **显式 WIRING** — `Main.java` 中的 `GsimapToolRegistrar.registerAll()` 调用是唯一的集成点。虽然没有自动发现的便利性，但依赖关系一目了然，新人通过阅读 Main.java 即可理解模块拓扑。
4. **双命名空间 UX** — MCP 用户需要同时记忆 `gsim_`（核心工具）和 `gsimap_`（地图工具）两组前缀。工具发现需要通过 `list_tools` 或 API 文档完成。
5. **前端双轨** — Web 浏览器需要打开两个地址（`:8710` 聊天界面、`:8711` 地图编辑器），使用不同的技术栈。两套前端仅在磁盘上的数据文件层面共享状态（同一份 `data/worlds/` 目录）。
