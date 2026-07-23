# ADR 0001: Maven 多模块结构

**Status:** Accepted

## Context

项目最初是一个单 Maven 模块。随着功能增长，三个不同的关注点逐渐分明：

- **(a) gsimap（十六进制地图编辑器）** 拥有独立的数据模型（MapData record、HexCell、Province、TerrainType、PathwayGroup、CompressedRegion、edges）、独立的 HTTP 服务器（端口 8711）、一套 Canvas 前端（14 个原生 JS 模块），以及 20 个 MCP 工具。
- **(b) gsim-app（CLI 入口）** 需要产出一个 shaded fat JAR，作为可分发的一站式命令行工具。
- **(c) gsim-lib（核心库）** 应该可以作为 Maven 依赖被其他应用嵌入，不绑定具体入口。

单模块结构下，这三者耦合在一起：核心库的每次改动都强迫构建 fat JAR，gsimap 的数据模型与 WorldInfo 系统互相可见，违背了"不用的东西不应该依赖"的原则。

同时参见 `ARCHITECTURE.md` 中关于"模块边界"的讨论。

## Decision

拆分为 **3 个 Maven 模块**，统一由 parent POM 管理：

```
gsim-parent
├── gsim-lib/    — 核心库（Agent、LLM、WorldInfo、MCP 接口、HTTP API、WebUI、Docs）
├── gsimap/      — 十六进制地图模块（MapData、MapService、20 个 MCP 工具、Canvas 前端、HTTP 服务器 :8711）
└── gsim-app/    — CLI 入口（只有一个 Main.java，产出 shaded fat JAR）
```

依赖链为 `gsim-app -> gsimap -> gsim-lib`，**不存在循环依赖**。具体约定：

- **gsim-lib** 不声明 main class，打包为普通 JAR，可作为 Maven 依赖被外部项目引用。它包含 Agent 核心、LLM 封装、WorldInfo 存储、事件系统、HTTP API 路由定义、WebUI 静态服务、以及对 MCP 协议的支持接口。
- **gsimap** 依赖 gsim-lib，利用 gsim-lib 的 `AgentTool` / `ToolRegistry` / `ToolCall` / `ToolResult` 接口注册工具，但持久化（`MapStore` + `MapResolver` 差异链重建）、HTTP 服务（`com.sun.net.httpserver.HttpServer`）、前端技术栈（Canvas）完全独立。
- **gsim-app** 依赖 gsim-lib 和 gsimap，通过 `maven-shade-plugin` 打出 fat JAR。工具注册在 `Main.java` 中显式调用 `GsimapToolRegistrar.registerAll()` 完成，不依赖自动发现机制。

## Consequences

1. **干净的职责分离** — gsim-lib 对十六进制地图零感知，不引入任何 gsimap 的 import。后续即使移除或替换 gsimap 模块，核心库不受影响。
2. **独立演进能力** — gsimap 可由不同团队或不同节奏开发，其数据模型、持久化策略、前端框架的选择不约束 gsim-lib。
3. **编译顺序敏感** — 多模块构建下 `gsim-lib` 必须先编译，`gsim-app` 最后。parent POM 的 `<modules>` 声明顺序需维护正确。
4. **手动注册** — gsimap 的 MCP 工具不在类路径扫描范围内，必须在 `Main.java` 中显式注册。这增加了 wiring 代码，但使依赖关系透明可审计。
5. **双命名空间** — MCP 用户看到 `gsim_`（核心工具）和 `gsimap_`（地图工具）两个前缀，需要通过文档理解各自的归属。
