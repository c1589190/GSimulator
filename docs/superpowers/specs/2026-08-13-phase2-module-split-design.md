# Phase 2 模块拆分设计（五模块目标架构）

日期：2026-08-13
前置：Phase 0-1 已完成（分支 feat/tool-mcp-refactor，HEAD f35e73a，已推送 origin）

## 1. 目标

将现有 3 个 Maven 模块（gsim-lib / gsim-app / gsimap）重组为 5 个模块，实现用户确定的依赖分层：

```
gsim-agentlib ── AgentTool 协议 + ToolRegistry + MCP 适配（零业务依赖，可独立打包复用）
    ↑                  ↑                      ↑
gsim-core         gsim-map             gsim-agent
(纯业务)          (地图业务)            ├── Agent 运行时（ToolLoop/Orchestrator/SubAgent）
(零 agentlib)     (依赖 core)          └── 桥接注册层：把 core/map 业务能力包装为 AgentTool
    ↑                  ↑                      ↑
                     gsim-app（Main + 组装 + 交互壳）
```

**Maven 依赖（唯一合法方向）：**
- gsim-agentlib：无内部依赖
- gsim-core：无内部依赖（零 agentlib 依赖——core 不含 AgentTool 概念）
- gsim-map：→ gsim-core
- gsim-agent：→ gsim-agentlib + gsim-core + gsim-map
- gsim-app：→ 全部四个

**循环依赖禁令**：core 不得依赖 agent/agentlib/map；map 不得依赖 agent/agentlib；agent 不得被 core/map 反向依赖。

## 2. 包命名规则

| 模块 | Java 包名前缀 | 说明 |
|---|---|---|
| gsim-agentlib | `com.gsim.agentlib.*` | 协议 `com.gsim.agentlib.tool`、MCP 适配 `com.gsim.agentlib.mcp`、内部工具 `com.gsim.agentlib.util` |
| gsim-core | `com.gsim.core.*` | 例如 `com.gsim.core.worldinfo`、`com.gsim.core.doc` |
| gsim-map | `com.gsim.map.*` | 原 `com.gsimap.*` 整体改名 |
| gsim-agent | `com.gsim.agent.*`（运行时，保留现名）+ `com.gsim.agent.tools.*`（桥接工具实现） | |
| gsim-app | `com.gsim.app.*`、`com.gsim.commands.*`、`com.gsim.interaction.*`、`com.gsim.webui.*`（保留现名）+ `com.gsim.Main` | 包名不再随模块改（已符合模块=包名模式） |

## 3. 逐模块内容与迁移映射

### 3.1 gsim-agentlib（新建模块，现 gsim-lib 的 tool/ + mcp/ 框架部分）

| 现状位置 | 迁移后 |
|---|---|
| `tool/AgentTool`、`ToolCall`、`ToolResult`、`ToolRegistry`、`ToolExecutionGuard`、`ToolCategory` | `com.gsim.agentlib.tool.*` |
| `mcp/AbstractMcpServer`、`McpHttpServer`、`StdioMcpTransport`、`ToolRegistryMcpAdapter`、`GsimRequestContext` | `com.gsim.agentlib.mcp.*` |
| `util/JsonUtils` | `com.gsim.agentlib.util.JsonUtils`（复制；core 保留一份供 map 用，见 3.2） |

**移出 agentlib**（业务性，归 agent 桥接层）：
- `tool/WikiSearchTool`、`MediaWikiSearchTool`、`LocalFileSearchService` → `com.gsim.agent.tools.*`
- `mcp/GsimMcpServer`、`McpStandaloneToolRegistry` → `com.gsim.agent.mcp.*`（服务实例组装 + 全量工具注册桥接）

**agentlib 的复用契约**：外部程序依赖 gsim-agentlib 后，实现自己的 `AgentTool`、注册进 `ToolRegistry`，即可用 `AbstractMcpServer`（HTTP 或 stdio）暴露 MCP。零业务依赖，jar 只含协议+适配+Jackson。

### 3.2 gsim-core（原 gsim-lib 收缩改名）

| 现状包 | 迁移后 | 备注 |
|---|---|---|
| `worldinfo/`（除 `tool/`） | `com.gsim.core.worldinfo.*` | WorldInformation/Checkpoint/Element/loader/manager 等业务模型 |
| `worldinfo/tool/`（14 个工具） | → agent（`com.gsim.agent.tools.worldinfo.*`） | 工具实现挪 agent |
| `doc/`（除 `tool/`） | `com.gsim.core.doc.*` | DocStore/DocCacheManager/Document |
| `doc/tool/`（9 个工具） | → agent（`com.gsim.agent.tools.doc.*`） | |
| `session/` | `com.gsim.core.session.*` | SessionPool/SessionNode/SessionPoolBridge |
| `importing/`（除 `tool/`） | `com.gsim.core.importing.*` | ImportDocumentService |
| `importing/tool/`（3 个） | → agent（`com.gsim.agent.tools.importing.*`） | |
| `cache/`（除 `tool/`） | `com.gsim.core.cache.*` | CacheSession/CacheStore/CachesManager |
| `cache/tool/` | → agent（`com.gsim.agent.tools.cache.*`） | |
| `compact/` | `com.gsim.core.compact.*` | CacheCompactor（业务，用 llm） |
| `llm/` | `com.gsim.core.llm.*` | LlmManager/Provider/StreamPool/LlmConfigManager |
| `event/` | `com.gsim.core.event.*` | EventBus/GSimEvent/AgentProgressSink 族 |
| `config/` | `com.gsim.core.config.*` | ConfigLoader/ConfigSource/ConfigDoctor/ConfigWizard |
| `skill/` | `com.gsim.core.skill.*` | SkillIndex |
| `embedding/` | `com.gsim.core.embedding.*` | EmbeddingClient |
| `webimport/` | `com.gsim.core.webimport.*` | MediaWikiApiClient |
| `ref/`（RefResolver，工具挪 agent） | `com.gsim.core.ref.*` | ResolveRefTool → agent |
| `text/`（TextEditor，工具挪 agent） | `com.gsim.core.text.*` | TextEditTool → agent |
| `util/`（IdGenerator/LogSanitizer/TimeProvider） | `com.gsim.core.util.*` | JsonUtils 复制到 agentlib；core 保留 JsonUtils 供 map 与 core 内部使用 |
| `agent/`、`mcp/`、`tool/` | 移出（见 3.1/3.4） | |
| `app/`、`commands/`、`interaction/`、`webui/` | 移出（见 3.5） | |

**core 的依赖边界**：core 内不得出现 `com.gsim.agentlib` / `com.gsim.agent` / `com.gsim.map` import。

### 3.3 gsim-map（原 gsimap 收缩改名）

| 现状位置 | 迁移后 |
|---|---|
| `com.gsimap.config.GsimapConfig` | `com.gsim.map.config.*` |
| `com.gsimap.map.*`（MapData/MapStore/MapResolver/MapDiff） | `com.gsim.map.*` |
| `com.gsimap.service.*`（MapService 等 14 类） | `com.gsim.map.service.*` |
| `com.gsimap.http.*`（GsimapHttpServer/MapWebUIHandler/StaticFileHandler） | `com.gsim.map.http.*` |
| `com.gsimap.tool.*`（25 个工具 + AbstractGsimapTool + GsimapToolRegistrar） | → agent（`com.gsim.agent.tools.map.*`） |
| resources：`web/` 前端静态资源 | 随模块保留 |
| resources：`logback-mcp.xml`、`META-INF/services`（已删） | 保留 logback-mcp.xml |

**map 的依赖边界**：依赖 gsim-core（NodeLoader attachment、AppConfig）；不得依赖 agentlib/agent。`GsimRequestContext` 的引用随工具移出 map。

### 3.4 gsim-agent（原 gsim-lib 的 agent/ 包 + 全部工具实现）

| 内容 | 迁移后包名 |
|---|---|
| 运行时：`agent/`（OrchestratorAgent 405 行、AbstractAgent 等）+ `agent/core/`、`agent/config/`、`agent/management/`、`agent/tool/`（13 个 agent 管理工具） | `com.gsim.agent.*`（保留现包名） |
| 服务组装：`mcp/GsimMcpServer`、`McpStandaloneToolRegistry` | `com.gsim.agent.mcp.*` |
| 工具实现（core 业务）：doc 9 + worldinfo 14 + importing 3 + cache 3 + ref 1 + text 1 | `com.gsim.agent.tools.{doc,worldinfo,importing,cache,ref,text}.*` |
| 工具实现（搜索）：WikiSearchTool、MediaWikiSearchTool、LocalFileSearchService | `com.gsim.agent.tools.search.*` |
| 工具实现（地图）：25 个 Gsimap*Tool + AbstractGsimapTool + GsimapToolRegistrar | `com.gsim.agent.tools.map.*` |

**agent 的桥接职责**：提供注册入口（形如 `AgentBridge.registerCoreTools(ToolRegistry, CoreContext)`、`AgentBridge.registerMapTools(ToolRegistry, MapService)`），由 gsim-app 组装时调用。`McpStandaloneToolRegistry` 在迁移中重构为注入式（消除硬编码 `new` 业务工具），具体 schema 在实现计划中细化。

### 3.5 gsim-app（入口 + 组装 + 交互壳）

| 现状位置 | 迁移后 |
|---|---|
| `gsim-app/.../com/gsim/Main.java` | 保留 |
| gsim-lib `app/`（GSimulatorApplication/AppConfig/Bootstrap/ApplicationContext） | `com.gsim.app.*`（挪入 gsim-app 模块） |
| gsim-lib `commands/`（7 个命令类） | `com.gsim.commands.*`（挪入） |
| gsim-lib `interaction/`（ConsoleInteractionAdapter 等） | `com.gsim.interaction.*`（挪入） |
| gsim-lib `webui/`（WebUiServer + 7 handlers + 静态资源） | `com.gsim.webui.*`（挪入） |

**app 的组装职责**：创建 core 业务对象（DocStore/WorldInformation/…）与 map 业务对象（MapService），调 agent 桥接注册工具，启动 WebUiServer/McpHttpServer/CLI REPL。禁止业务逻辑（遵守 CLAUDE.md 既有禁令）。

## 4. 关键设计决策记录（用户拍板）

1. **协议+工具全部归 agent 侧**：core/map 只提供代码内业务接口（领域对象），不实现 AgentTool；工具实现打包在 gsim-agent（2026-08-13 对话中断后用户重新决策）。
2. **独立可复用模块命名 gsim-agentlib**（初名 toolib，用户改名）：AgentTool/MCP 标准接口独立打包，供 GoatMosire 等其他程序复用（用户最初诉求）。
3. **包名同步模块前缀**：com.gsim.core.* / com.gsim.map.* / com.gsim.agentlib.*（用户选择）。
4. **commands/interaction/webui 归 gsim-app**（用户确认，避免 core→agent 循环）。
5. **gsim-agent 依赖 gsim-map**（用户确认"按原话"）：地图工具实现迁入 agent，agent 的 jar 传递携带 map；其他程序复用 agent 时会连带 map（用户知悉并接受）。

## 5. 风险与对策

| 风险 | 对策 |
|---|---|
| 包改名（com.gsimap→com.gsim.map、com.gsim→com.gsim.core 等）波及面大（~200 文件） | 分模块逐步迁移：每步 `mvn clean test` 验证；用机械替换（serena replace_in_files / sed）+ 编译错误驱动收尾 |
| 工具实现挪 agent 时与业务类之间的隐性耦合（如工具直接访问 package-private 成员） | 迁移任务内先 grep 工具类对业务包的所有引用面；必要时放宽访问级别（public）并在 commit 说明 |
| agentlib 的 JsonUtils 复制与 core 副本漂移 | JsonUtils 为纯静态 JSON 工具且极少变更；spec 注明两份副本的同步义务；Phase 4 可考虑引入独立 util 模块 |
| agent 依赖 map 导致的 jar 体积/传递依赖 | 用户已接受；agentlib 保持零业务依赖，复用方若只想要协议+ MCP 就只依赖 agentlib |
| 迁移期间编译中间态 | 每个任务以"可编译、测试绿"为提交门槛，不产生跨任务编译断裂 |

## 6. 验收标准（Phase 2 完成判据）

1. 根 pom 模块列表 = gsim-agentlib / gsim-core / gsim-agent / gsim-map / gsim-app（5 个）
2. `mvn clean test` 全绿；`mvn spotless:apply` 后 `mvn verify` 通过（含 Phase 0-1 终审发现的 MapWebUIHandler 格式违规修复）
3. 依赖方向合法：`mvn dependency:tree` 核验无 core→agent、map→agent、core→agentlib、map→agentlib 边
4. core 内 grep `com.gsim.agent`/`com.gsim.agentlib`/`com.gsim.map` 零命中；map 内 grep `com.gsim.agent`/`com.gsim.agentlib` 零命中
5. 运行验证：`java -jar gsim-app/target/gsim-app-*.jar` 启动后 WebUI(8710)/Map UI(8711)/MCP(8720) 三服务正常；MCP tools/list 包含全部工具（core 业务 + 地图 + agent 管理）
6. 终审遗留的 2 项 Important 已处理：工具名残留清理（ToolGroup/prompt/CLAUDE.md 的 node_switch 等）、spotbugs baseline 重建（Phase 2 内用 `mvn spotbugs:spotbugs -pl gsim-core` 重新生成）

## 7. 与后续阶段的衔接

- **Phase 3（配置系统）**：config/ 目录 + core_*/agent_*/map_*.json 命名、llms.json `${任意环境变量}` 引用、三条 llms.json 解析路径合一——Phase 2 不涉及
- **Phase 4（重合点收口）**：权限链统一、SubAgent 派发统一、双缓存统一、工具权限配置 schema——Phase 2 不涉及（迁移时保持现状行为）
- **工具权限机制细化**：用户指示"重构好之后细化"，Phase 2 只迁移不重设计
