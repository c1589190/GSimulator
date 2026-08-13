# Phase 2 五模块拆分实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 将现有 3 个 Maven 模块（gsim-lib / gsim-app / gsimap）重组为 spec 定义的五模块目标架构（gsim-agentlib / gsim-core / gsim-agent / gsim-map / gsim-app），包名与模块同步，依赖方向合规。

**Architecture:** 自底向上提取：先抽出零业务依赖的 gsim-agentlib（协议 + MCP 框架），再改 gsim-lib 为 gsim-core，然后先提取 gsim-app（消除 app→agent 编译边），再提取 gsim-agent（运行时 + 全部业务工具 + MCP 组装），再做 core/map 包改名，最后 AgentBridge 收口与质量/文档收尾。每个任务以"可编译、测试绿"为提交门槛。

**Tech Stack:** Java 21 多模块 Maven、Jackson、OkHttp、SLF4J/Log4j2、JLine、Thymeleaf、JDK com.sun.net.httpserver、JUnit 5、Spotless/Checkstyle/SpotBugs。

**Spec:** `docs/superpowers/specs/2026-08-13-phase2-module-split-design.md`（已批准，d1890f9）

**起点:** 分支 feat/tool-mcp-refactor，HEAD d1890f9，工作区干净。

## Global Constraints

- **提交门槛（每任务）**：控制器在任务完成后运行 `mvn clean test --batch-mode`，必须 BUILD SUCCESS（当前基线：587 tests, 0 failures）；失败进入 fix loop。
- **执行约定（用户指定）**：implementer 子代理只改代码（可运行只读命令 grep/ls/find/git status/git diff），**不得运行 mvn/java 等构建测试命令，不得 commit**；构建验证由控制器执行。控制器可在任务验证阶段运行 `mvn spotless:apply` 作为格式化收尾（T9 及其后的例外）。
- **编译严格模式**：parent pom 启用 `-Xlint:all,-processing,-options` 与 `-Werror` —— 未使用 import、deprecation 等均会编译失败。机械改名（sed）必须同步清理未使用 import；每步 sed 后紧跟残留 grep 验证。
- **目标模块名与包名前缀（verbatim，不可改动）**：
  | 模块 | 包名前缀 |
  |---|---|
  | gsim-agentlib | `com.gsim.agentlib.tool.*`（协议）、`com.gsim.agentlib.mcp.*`（MCP 框架）、`com.gsim.agentlib.util.*` |
  | gsim-core | `com.gsim.core.*` |
  | gsim-map | `com.gsim.map.*` |
  | gsim-agent | `com.gsim.agent.*`（运行时保留现名）、`com.gsim.agent.tools.*`（工具实现）、`com.gsim.agent.mcp.*`（服务组装）、`com.gsim.agent.bridge.*`（桥接） |
  | gsim-app | `com.gsim.app.*`、`com.gsim.commands.*`、`com.gsim.interaction.*`、`com.gsim.webui.*`、`com.gsim.Main`（保留现名，不改） |
- **依赖方向禁令（终态）**：core 不得依赖 agent/agentlib/map；map 不得依赖 agent/agentlib；agent → agentlib + core + map；app → 全部四个。判定方式：源码 import 零命中 + pom 无依赖边。
- **允许的临时依赖边**（对应任务结束时拆除）：T1-T4 期间 core pom 有 agentlib 边（业务工具尚在 core 内）；T1-T6b 期间 map pom 有 agentlib 边（地图工具尚在 map 内）。两者拆除时用 grep 验证源码零命中。
- **JsonUtils 双副本契约**：agentlib 内部文件一律使用本模块 `com.gsim.agentlib.util.JsonUtils`；core/agent/map/app 其余代码使用 core 副本（T5 后为 `com.gsim.core.util.JsonUtils`）。两份副本同步义务见 spec §5。
- **行为不变原则**：除 T3（删除 GsimMcpServer 弃用路径与 McpStandaloneToolRegistry）、T7（注册代码搬家）、T8（删除已删工具的残留引用）外，所有改动只搬家 + 改名，不改逻辑、不加功能。T7/T8 不改变任何工具的运行行为。
- **不做 Phase 3/4 内容**：配置系统（config/ 目录、llms.json 环境变量引用）、权限链统一、SubAgent 派发统一、双缓存统一——一律不在此计划内。
- **改动范围纪律**：每个任务只动 Files 清单列出的内容；发现清单外问题写入报告，不顺手改。
- **SDD 执行**：每任务一位 implementer（只改代码）→ 控制器验证 → 任务审查；全部完成后终审 + finishing-a-development-branch。任务间不并行实现。

## 计划对 spec 的两处偏差（显式记录，供审查员与用户知悉）

1. **ToolCategory / ToolCategoryRegistry 留在 gsim-agent（spec §3.1 表格曾列 ToolCategory 归 agentlib）**。实测：ToolCategory 位于 `agent/` 包（非 tool/），仅被 ToolExecutionPolicy/ToolFilterEvaluator/ToolGroupManager/OrchestratorAgent 等 agent 运行时类引用；ToolRegistryMcpAdapter 不引用它。移入 agentlib 会引入 agentlib→agent 非法边，且分类属业务权限概念而非协议。故 spec 表格中的该行以本计划为准。
2. **McpStandaloneToolRegistry 删除而非迁移重构（spec §3.1/§3.4 曾计划移入 com.gsim.agent.mcp 并注入式重构）**。实测：该类为 `@Deprecated`（"will be removed"），唯一调用者是 `GsimMcpServer.main()` 的弃用路径（T3 删除）。删除即消除其硬编码 `new` 业务工具问题；独立注册入口由 T7 的 AgentBridge 承担（与 spec §3.4 桥接职责一致）。GsimMcpServer 本身照 spec 移入 com.gsim.agent.mcp（弃用成员拆除）。

## 任务依赖顺序（为什么 app 先于 agent）

GsimMcpServer / McpStandaloneToolRegistry 当前 import `com.gsim.app.ApplicationContext`。若先提取 gsim-agent，agent 模块将依赖 app 包（core 与 app 都装不下它），形成非法边。因此顺序为：T1 agentlib → T2 core 改名 → **T3 app 提取（同时拆除 mcp→app 边）** → T4 agent 提取 → T5 core 包改名 → T6 map 重组 → T7 桥接收口 → T8/T9/T10 收尾。T4 之前 agent/、tool/、mcp/ 包物理上仍在 gsim-core 模块内（无需 pom 边），这是合法中间态。

---

### Task 1: gsim-agentlib 提取（协议 + MCP 框架 + JsonUtils 副本）

**Files:**
- Create: `gsim-agentlib/pom.xml`
- Create: `gsim-agentlib/src/main/java/com/gsim/agentlib/tool/{AgentTool,ToolCall,ToolResult,ToolRegistry,ToolExecutionGuard}.java`（自 gsim-lib `tool/` git mv，改 package）
- Create: `gsim-agentlib/src/main/java/com/gsim/agentlib/mcp/{AbstractMcpServer,McpHttpServer,StdioMcpTransport,McpTransport,ToolRegistryMcpAdapter,GsimRequestContext,UnknownToolException,ToolDef,McpToolRegistry,CompositeMcpToolRegistry,CloseShieldInputStream}.java`（自 gsim-lib `mcp/` git mv，改 package）
- Create: `gsim-agentlib/src/main/java/com/gsim/agentlib/util/JsonUtils.java`（自 gsim-lib `util/JsonUtils.java` **复制**，原文件保留在 gsim-lib）
- Create: `gsim-agentlib/src/test/java/com/gsim/agentlib/mcp/{McpProtocolTest,McpHttpServerTest,McpExposedFilterTest}.java`（自 gsim-lib test `mcp/` git mv，改 package）
- Modify: 根 `pom.xml`、`gsim-lib/pom.xml`、`gsimap/pom.xml`
- Modify: gsim-lib / gsimap / gsim-app 中所有 import 协议类的源文件与测试（sed，见 Step 4）

**Interfaces:**
- Consumes: 无（本任务为基座）
- Produces: `com.gsim.agentlib.tool.{AgentTool,ToolCall,ToolResult,ToolRegistry,ToolExecutionGuard}`；`com.gsim.agentlib.mcp.{AbstractMcpServer,McpHttpServer,StdioMcpTransport,McpTransport,ToolRegistryMcpAdapter,GsimRequestContext,UnknownToolException,ToolDef,McpToolRegistry,CompositeMcpToolRegistry,CloseShieldInputStream}`；`com.gsim.agentlib.util.JsonUtils`；Maven 坐标 `com.gsim:gsim-agentlib:${project.version}`（T2 起被依赖）

- [ ] **Step 1: 创建模块骨架**

`gsim-agentlib/pom.xml` 内容（parent 为 `com.gsim:GSimulator:0.1.0-Alpha260723`）：

```xml
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0" xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance" xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 http://maven.apache.org/xsd/maven-4.0.0.xsd">
    <modelVersion>4.0.0</modelVersion>

    <parent>
        <groupId>com.gsim</groupId>
        <artifactId>GSimulator</artifactId>
        <version>0.1.0-Alpha260723</version>
    </parent>

    <artifactId>gsim-agentlib</artifactId>
    <packaging>jar</packaging>

    <name>GSim AgentLib</name>
    <description>AgentTool 协议 + ToolRegistry + MCP 适配层 — 零业务依赖，可独立打包供外部程序复用</description>

    <dependencies>
        <dependency>
            <groupId>com.fasterxml.jackson.core</groupId>
            <artifactId>jackson-databind</artifactId>
        </dependency>
        <dependency>
            <groupId>org.slf4j</groupId>
            <artifactId>slf4j-api</artifactId>
        </dependency>
        <dependency>
            <groupId>org.junit.jupiter</groupId>
            <artifactId>junit-jupiter</artifactId>
            <scope>test</scope>
        </dependency>
    </dependencies>

    <build>
        <plugins>
            <!-- SpotBugs: 新模块零容忍 -->
            <plugin>
                <groupId>com.github.spotbugs</groupId>
                <artifactId>spotbugs-maven-plugin</artifactId>
                <configuration>
                    <effort>Max</effort>
                    <threshold>Medium</threshold>
                    <failOnError>true</failOnError>
                    <includeTests>false</includeTests>
                </configuration>
            </plugin>
        </plugins>
    </build>
</project>
```

根 `pom.xml`：`<modules>` 中在 `<module>gsim-lib</module>` 之前插入 `<module>gsim-agentlib</module>`。

- [ ] **Step 2: git mv 移动 16 个主代码文件 + 3 个测试文件并改 package 声明**

```bash
# 主代码（工具协议 5 个 → com.gsim.agentlib.tool）
git mv gsim-lib/src/main/java/com/gsim/tool/AgentTool.java gsim-agentlib/src/main/java/com/gsim/agentlib/tool/AgentTool.java
git mv gsim-lib/src/main/java/com/gsim/tool/ToolCall.java gsim-agentlib/src/main/java/com/gsim/agentlib/tool/ToolCall.java
git mv gsim-lib/src/main/java/com/gsim/tool/ToolResult.java gsim-agentlib/src/main/java/com/gsim/agentlib/tool/ToolResult.java
git mv gsim-lib/src/main/java/com/gsim/tool/ToolRegistry.java gsim-agentlib/src/main/java/com/gsim/agentlib/tool/ToolRegistry.java
git mv gsim-lib/src/main/java/com/gsim/tool/ToolExecutionGuard.java gsim-agentlib/src/main/java/com/gsim/agentlib/tool/ToolExecutionGuard.java

# MCP 框架 11 个 → com.gsim.agentlib.mcp
git mv gsim-lib/src/main/java/com/gsim/mcp/AbstractMcpServer.java gsim-agentlib/src/main/java/com/gsim/agentlib/mcp/AbstractMcpServer.java
git mv gsim-lib/src/main/java/com/gsim/mcp/McpHttpServer.java gsim-agentlib/src/main/java/com/gsim/agentlib/mcp/McpHttpServer.java
git mv gsim-lib/src/main/java/com/gsim/mcp/StdioMcpTransport.java gsim-agentlib/src/main/java/com/gsim/agentlib/mcp/StdioMcpTransport.java
git mv gsim-lib/src/main/java/com/gsim/mcp/McpTransport.java gsim-agentlib/src/main/java/com/gsim/agentlib/mcp/McpTransport.java
git mv gsim-lib/src/main/java/com/gsim/mcp/ToolRegistryMcpAdapter.java gsim-agentlib/src/main/java/com/gsim/agentlib/mcp/ToolRegistryMcpAdapter.java
git mv gsim-lib/src/main/java/com/gsim/mcp/GsimRequestContext.java gsim-agentlib/src/main/java/com/gsim/agentlib/mcp/GsimRequestContext.java
git mv gsim-lib/src/main/java/com/gsim/mcp/UnknownToolException.java gsim-agentlib/src/main/java/com/gsim/agentlib/mcp/UnknownToolException.java
git mv gsim-lib/src/main/java/com/gsim/mcp/ToolDef.java gsim-agentlib/src/main/java/com/gsim/agentlib/mcp/ToolDef.java
git mv gsim-lib/src/main/java/com/gsim/mcp/McpToolRegistry.java gsim-agentlib/src/main/java/com/gsim/agentlib/mcp/McpToolRegistry.java
git mv gsim-lib/src/main/java/com/gsim/mcp/CompositeMcpToolRegistry.java gsim-agentlib/src/main/java/com/gsim/agentlib/mcp/CompositeMcpToolRegistry.java
git mv gsim-lib/src/main/java/com/gsim/mcp/CloseShieldInputStream.java gsim-agentlib/src/main/java/com/gsim/agentlib/mcp/CloseShieldInputStream.java

# JsonUtils 复制（gsim-lib 原文件保留不动）
cp gsim-lib/src/main/java/com/gsim/util/JsonUtils.java gsim-agentlib/src/main/java/com/gsim/agentlib/util/JsonUtils.java

# 测试 3 个
git mv gsim-lib/src/test/java/com/gsim/mcp/McpProtocolTest.java gsim-agentlib/src/test/java/com/gsim/agentlib/mcp/McpProtocolTest.java
git mv gsim-lib/src/test/java/com/gsim/mcp/McpHttpServerTest.java gsim-agentlib/src/test/java/com/gsim/agentlib/mcp/McpHttpServerTest.java
git mv gsim-lib/src/test/java/com/gsim/mcp/McpExposedFilterTest.java gsim-agentlib/src/test/java/com/gsim/agentlib/mcp/McpExposedFilterTest.java
```

对新落位文件执行 package 声明替换（sed）：

```bash
sed -i 's/^package com\.gsim\.tool;/package com.gsim.agentlib.tool;/' gsim-agentlib/src/main/java/com/gsim/agentlib/tool/*.java
sed -i 's/^package com\.gsim\.mcp;/package com.gsim.agentlib.mcp;/' gsim-agentlib/src/main/java/com/gsim/agentlib/mcp/*.java gsim-agentlib/src/test/java/com/gsim/agentlib/mcp/*.java
sed -i 's/^package com\.gsim\.util;/package com.gsim.agentlib.util;/' gsim-agentlib/src/main/java/com/gsim/agentlib/util/JsonUtils.java
```

- [ ] **Step 3: 修正 agentlib 内部 import**

agentlib 内部互引（协议 ↔ MCP 框架 ↔ JsonUtils）在新包下修正：

```bash
# MCP 框架类引 tool 协议 → agentlib.tool
sed -i 's/import com\.gsim\.tool\./import com.gsim.agentlib.tool./g' gsim-agentlib/src/main/java/com/gsim/agentlib/mcp/*.java gsim-agentlib/src/test/java/com/gsim/agentlib/mcp/*.java
# agentlib 内引 JsonUtils → 本模块副本
sed -i 's/import com\.gsim\.util\.JsonUtils;/import com.gsim.agentlib.util.JsonUtils;/g' gsim-agentlib/src/main/java/com/gsim/agentlib/mcp/*.java
# MCP 框架互引（同包 import 若存在则删除——同包类无需 import）
grep -rn "import com\.gsim\.mcp\." gsim-agentlib/src | grep -v "import com.gsim.mcp" || true
# 若上述 grep 有命中，逐行删除（同包类）
```

同时删除移动后产生的未使用 import（`-Werror` 约束）：对 agentlib 每个文件执行 `grep "^import"` 人工核对（只读操作），删除不再使用的行。

- [ ] **Step 4: 全仓 import 重写（gsim-lib 剩余文件 + gsimap + gsim-app）**

```bash
cd /home/cna/DevMosire/GSimulator
for d in gsim-lib gsimap gsim-app; do
  find $d/src -name "*.java" -print0 | xargs -0 sed -i \
    -e 's/import com\.gsim\.tool\.AgentTool\(\.Permission\)\?;/import com.gsim.agentlib.tool.AgentTool\1;/g' \
    -e 's/import com\.gsim\.tool\.ToolCall;/import com.gsim.agentlib.tool.ToolCall;/g' \
    -e 's/import com\.gsim\.tool\.ToolResult;/import com.gsim.agentlib.tool.ToolResult;/g' \
    -e 's/import com\.gsim\.tool\.ToolRegistry;/import com.gsim.agentlib.tool.ToolRegistry;/g' \
    -e 's/import com\.gsim\.tool\.ToolExecutionGuard;/import com.gsim.agentlib.tool.ToolExecutionGuard;/g' \
    -e 's/import com\.gsim\.mcp\.AbstractMcpServer;/import com.gsim.agentlib.mcp.AbstractMcpServer;/g' \
    -e 's/import com\.gsim\.mcp\.McpHttpServer;/import com.gsim.agentlib.mcp.McpHttpServer;/g' \
    -e 's/import com\.gsim\.mcp\.StdioMcpTransport;/import com.gsim.agentlib.mcp.StdioMcpTransport;/g' \
    -e 's/import com\.gsim\.mcp\.McpTransport;/import com.gsim.agentlib.mcp.McpTransport;/g' \
    -e 's/import com\.gsim\.mcp\.ToolRegistryMcpAdapter;/import com.gsim.agentlib.mcp.ToolRegistryMcpAdapter;/g' \
    -e 's/import com\.gsim\.mcp\.GsimRequestContext;/import com.gsim.agentlib.mcp.GsimRequestContext;/g' \
    -e 's/import com\.gsim\.mcp\.UnknownToolException;/import com.gsim.agentlib.mcp.UnknownToolException;/g' \
    -e 's/import com\.gsim\.mcp\.ToolDef;/import com.gsim.agentlib.mcp.ToolDef;/g' \
    -e 's/import com\.gsim\.mcp\.McpToolRegistry;/import com.gsim.agentlib.mcp.McpToolRegistry;/g' \
    -e 's/import com\.gsim\.mcp\.CompositeMcpToolRegistry;/import com.gsim.agentlib.mcp.CompositeMcpToolRegistry;/g' \
    -e 's/import com\.gsim\.mcp\.CloseShieldInputStream;/import com.gsim.agentlib.mcp.CloseShieldInputStream;/g'
done
```

**注意**：`com.gsim.mcp.GsimMcpServer` 与 `com.gsim.mcp.McpStandaloneToolRegistry` 不在上述替换清单内（留在 gsim-lib，T3/T4 处理）。`com.gsim.util.JsonUtils` 的 import 全部**保持原样**（core 副本）。

FQN 使用点重写（如 `com.gsim.mcp.GsimRequestContext.worldId()`）：

```bash
for d in gsim-lib gsimap gsim-app; do
  find $d/src -name "*.java" -print0 | xargs -0 sed -i \
    -e 's/com\.gsim\.tool\.AgentTool\(\.Permission\)\?/com.gsim.agentlib.tool.AgentTool\1/g' \
    -e 's/com\.gsim\.tool\.ToolCall/com.gsim.agentlib.tool.ToolCall/g' \
    -e 's/com\.gsim\.tool\.ToolResult/com.gsim.agentlib.tool.ToolResult/g' \
    -e 's/com\.gsim\.tool\.ToolRegistry/com.gsim.agentlib.tool.ToolRegistry/g' \
    -e 's/com\.gsim\.tool\.ToolExecutionGuard/com.gsim.agentlib.tool.ToolExecutionGuard/g' \
    -e 's/com\.gsim\.mcp\.GsimRequestContext/com.gsim.agentlib.mcp.GsimRequestContext/g' \
    -e 's/com\.gsim\.mcp\.CompositeMcpToolRegistry/com.gsim.agentlib.mcp.CompositeMcpToolRegistry/g' \
    -e 's/com\.gsim\.mcp\.ToolRegistryMcpAdapter/com.gsim.agentlib.mcp.ToolRegistryMcpAdapter/g' \
    -e 's/com\.gsim\.mcp\.AbstractMcpServer/com.gsim.agentlib.mcp.AbstractMcpServer/g' \
    -e 's/com\.gsim\.mcp\.StdioMcpTransport/com.gsim.agentlib.mcp.StdioMcpTransport/g'
done
```

**警告**：此 sed 必须先于 import 重写执行或与之一同执行（import 行已被第一条 sed 改写后，`com\.gsim\.tool\.` 不再命中——幂等安全）；`com.gsim.tool.WikiSearchTool` 等业务工具不在替换清单内，不受影响。

残留验证（必须零命中，`GsimMcpServer`/`McpStandaloneToolRegistry`/`JsonUtils` 除外）：

```bash
grep -rn "import com\.gsim\.\(tool\.\(AgentTool\|ToolCall\|ToolResult\|ToolRegistry\|ToolExecutionGuard\)\|mcp\.\(AbstractMcpServer\|McpHttpServer\|StdioMcpTransport\|McpTransport\|ToolRegistryMcpAdapter\|GsimRequestContext\|UnknownToolException\|ToolDef\|McpToolRegistry\|CompositeMcpToolRegistry\|CloseShieldInputStream\)\)" gsim-lib/src gsimap/src gsim-app/src
grep -rn "com\.gsim\.\(tool\|mcp\)\." gsim-lib/src/main/java/com/gsim/tool gsim-lib/src/main/java/com/gsim/mcp | grep -v "GsimMcpServer\|McpStandaloneToolRegistry"
```

- [ ] **Step 5: pom 依赖接线**

1. `gsim-lib/pom.xml`：在 `<dependencies>` 内（Jackson 依赖之前）加入：

```xml
        <!-- GSim AgentLib (AgentTool 协议 + MCP 框架) — 临时依赖，T4 随 agent 提取拆除 -->
        <dependency>
            <groupId>com.gsim</groupId>
            <artifactId>gsim-agentlib</artifactId>
            <version>${project.version}</version>
        </dependency>
```

2. `gsimap/pom.xml`：加入同样的 gsim-agentlib 依赖（地图工具用协议类；T6b 拆除）。

3. `McpExternalLibTest.java`（仍在 gsim-lib test）的 javadoc 中 "gsim-lib" 字样暂不动（T4 随文件移动时统一改）。

- [ ] **Step 6: 报告**

报告内容：移动文件清单、sed 执行范围、残留 grep 结果（贴出零命中证据）、agentlib 内部未使用 import 清理清单。**不运行 mvn、不 commit。**

**验证（控制器）**：`mvn clean test --batch-mode` 必须 BUILD SUCCESS。失败（典型：漏改 import、未使用 import 触发 -Werror）进入 fix loop。

---

### Task 2: gsim-lib → gsim-core 模块改名（仅改名，无包名改动）

**Files:**
- Modify: 根 `pom.xml`、`gsim-core/pom.xml`（原 gsim-lib/pom.xml）、`gsim-app/pom.xml`、`gsimap/pom.xml`

**Interfaces:**
- Consumes: T1 的 gsim-agentlib
- Produces: Maven 坐标 `com.gsim:gsim-core:${project.version}`（原 gsim-lib 坐标消失；本任务后所有模块内引用一律用 gsim-core）

- [ ] **Step 1: git mv**

```bash
git mv gsim-lib gsim-core
```

- [ ] **Step 2: pom 编辑**

1. `gsim-core/pom.xml`：`<artifactId>gsim-lib</artifactId>` → `<artifactId>gsim-core</artifactId>`；`<name>GSim Library</name>` → `<name>GSim Core</name>`；description 改为：`GSimulator 核心库 — 世界管理（WorldInfo）、文档管理（Doc）、LLM、缓存、会话、事件、配置等业务层。不含 Agent 运行时与工具实现（见 gsim-agent）。`
2. 根 `pom.xml`：`<module>gsim-lib</module>` → `<module>gsim-core</module>`
3. `gsim-app/pom.xml`：`<artifactId>gsim-lib</artifactId>` → `<artifactId>gsim-core</artifactId>`（依赖块内）；description `thin wrapper around gsim-lib` → `GSimulator 程序入口 + 应用组装 + 交互壳（CLI/WebUI）`
4. `gsimap/pom.xml`：artifactId 同步改；依赖上方注释 `<!-- GSimulator library (Agent, WorldInfo, MCP base, HTTP API, WebUI) -->` → `<!-- GSimulator core (WorldInfo, Doc, LLM, Cache) -->`

- [ ] **Step 3: 残留检查与报告**

```bash
grep -rn "gsim-lib" --include="pom.xml" .
grep -rn "gsim-lib" gsim-core/pom.xml gsim-app gsimap pom.xml
```

两个 grep 均须零命中（`config/spotbugs/check-baseline.sh` 与 CLAUDE.md/docs 中的 gsim-lib 字样分别留给 T9/T10，报告中注明）。

**验证（控制器）**：`mvn clean test --batch-mode` 必须 BUILD SUCCESS。

---

### Task 3: gsim-app 提取（app/commands/interaction/webui 迁入，拆除 mcp→app 边）

**Files:**
- Create: gsim-app 内新目录（git mv 自 gsim-core）：
  - `gsim-app/src/main/java/com/gsim/app/`（GSimulatorApplication/AppConfig/Bootstrap/ApplicationContext 4 个文件）
  - `gsim-app/src/main/java/com/gsim/commands/`（7 个命令类）
  - `gsim-app/src/main/java/com/gsim/interaction/`（8 个类）
  - `gsim-app/src/main/java/com/gsim/webui/`（WebUiServer/WebUiConfig/TemplateRenderer/MermaidGraphBuilder/CliWsProgressSink/CliWebSocketServer + `handlers/` 8 个文件）
  - `gsim-app/src/test/java/com/gsim/app/BootstrapTest.java`
  - `gsim-app/src/test/java/com/gsim/interaction/{CommandParserTest,CliInputSanitizerTest}.java`
  - `gsim-app/src/test/java/com/gsim/integration/EndToEndTest.java`
  - `gsim-app/src/test/java/com/gsim/config/{ContextSessionHistoryTurnsConfigDefaultTest,AgentToolLoopMaxRoundsEnvMappingTest,AgentToolLoopMaxRoundsConfigTest}.java`
  - `gsim-app/src/main/resources/webui/`（static/ 6 文件 + templates/ 12 文件）
  - `gsim-app/src/main/resources/gsim/agent-api-guide.md`
- Modify: `gsim-core/src/main/java/com/gsim/mcp/GsimMcpServer.java`（拆 ApplicationContext 边 + 删弃用路径）
- Delete: `gsim-core/src/main/java/com/gsim/mcp/McpStandaloneToolRegistry.java`
- Modify: `gsim-app/pom.xml`、`gsim-core/pom.xml`

**Interfaces:**
- Consumes: T2 的 gsim-core；T1 的 gsim-agentlib（ToolRegistry/McpHttpServer）
- Produces: gsim-app 模块承载全部交互壳；`com.gsim.mcp.GsimMcpServer` 不再引用 `com.gsim.app`（为 T4 迁入 agent 铺路）

- [ ] **Step 1: git mv 包与测试、资源**

```bash
cd /home/cna/DevMosire/GSimulator
git mv gsim-core/src/main/java/com/gsim/app gsim-app/src/main/java/com/gsim/app
git mv gsim-core/src/main/java/com/gsim/commands gsim-app/src/main/java/com/gsim/commands
git mv gsim-core/src/main/java/com/gsim/interaction gsim-app/src/main/java/com/gsim/interaction
git mv gsim-core/src/main/java/com/gsim/webui gsim-app/src/main/java/com/gsim/webui
git mv gsim-core/src/test/java/com/gsim/app/BootstrapTest.java gsim-app/src/test/java/com/gsim/app/BootstrapTest.java
git mv gsim-core/src/test/java/com/gsim/interaction gsim-app/src/test/java/com/gsim/interaction
git mv gsim-core/src/test/java/com/gsim/integration gsim-app/src/test/java/com/gsim/integration
git mv gsim-core/src/test/java/com/gsim/config gsim-app/src/test/java/com/gsim/config
git mv gsim-core/src/main/resources/webui gsim-app/src/main/resources/webui
git mv gsim-core/src/main/resources/gsim/agent-api-guide.md gsim-app/src/main/resources/gsim/agent-api-guide.md
```

包名不变（`com.gsim.app.*` 等保留）。移动后 `gsim-core/src/main/resources/gsim/` 下只剩 `agents/`、`prompts/`、`templates/`（T4 再迁）。

- [ ] **Step 2: GsimMcpServer 拆边 + 删除弃用路径**

编辑 `gsim-core/src/main/java/com/gsim/mcp/GsimMcpServer.java`（189 行，全文重写比逐段删更安全）：

1. 删除 `import com.gsim.app.ApplicationContext;`
2. 删除两个以 `ApplicationContext` 为参数的构造函数（`GsimMcpServer(ApplicationContext ctx)` 与 `GsimMcpServer(ApplicationContext ctx, Supplier<String> activeWorldId)`）
3. 删除 `@Deprecated public static void main(String[] args)`、`startMapHttpServer(...)`、`tryRegisterMapTools(...)` 三个弃用成员（它们引用 McpStandaloneToolRegistry 与反射式 map 启动）
4. 保留：`GsimMcpServer(ToolRegistry)`、`(ToolRegistry, McpTransport)`、`(ToolRegistry, Supplier<String>)`、`(ToolRegistry, Supplier<String>, McpTransport)` 四个构造函数与 `getServerName()/getServerVersion()/getToolRegistry()`
5. 类 javadoc 的 Usage 示例改为仅保留 `new GsimMcpServer(toolRegistry)` 一例；类注释中 `@deprecated` 相关描述删除

- [ ] **Step 3: 删除 McpStandaloneToolRegistry**

```bash
git rm gsim-core/src/main/java/com/gsim/mcp/McpStandaloneToolRegistry.java
```

理由（报告内写明）：该类为 `@Deprecated`（"will be removed"），唯一调用者是 Step 2 已删除的 `GsimMcpServer.main()`；删除即消除其硬编码 `new` 业务工具问题（spec §3.4 的注入式重构目标以 AgentBridge 在 T7 实现）。残留验证：`grep -rn "McpStandaloneToolRegistry" gsim-core/src gsim-app/src gsimap/src` 零命中。

- [ ] **Step 4: pom 编辑**

`gsim-app/pom.xml` dependencies 替换为：

```xml
        <dependency>
            <groupId>com.gsim</groupId>
            <artifactId>gsim-core</artifactId>
            <version>${project.version}</version>
        </dependency>
        <dependency>
            <groupId>com.gsim</groupId>
            <artifactId>gsimap</artifactId>
            <version>${project.version}</version>
        </dependency>
        <dependency>
            <groupId>com.gsim</groupId>
            <artifactId>gsim-agentlib</artifactId>
            <version>${project.version}</version>
        </dependency>
        <!-- 交互壳直接依赖（app/commands/interaction/webui 迁入后不再经 core 传递） -->
        <dependency>
            <groupId>org.jline</groupId>
            <artifactId>jline</artifactId>
        </dependency>
        <dependency>
            <groupId>org.thymeleaf</groupId>
            <artifactId>thymeleaf</artifactId>
        </dependency>
        <dependency>
            <groupId>org.slf4j</groupId>
            <artifactId>slf4j-api</artifactId>
        </dependency>
        <dependency>
            <groupId>org.junit.jupiter</groupId>
            <artifactId>junit-jupiter</artifactId>
            <scope>test</scope>
        </dependency>
```

（jackson 经 gsim-core/gsim-agentlib 传递；spotbugs-annotations 保留原样；shade 配置不动。）

`gsim-core/pom.xml` dependencies 删除：`thymeleaf`（唯一使用者 TemplateRenderer 已迁走）、`sqlite-jdbc`（全仓零 `org.sqlite`/`java.sql` 引用，已核验）、`jsoup`（全仓零 `org.jsoup` 引用，已核验）。**保留** jline（agent/CliAgentProgressSink 尚在 core，T4 拆除）、junit、mockwebserver。

- [ ] **Step 5: 残留检查与报告**

```bash
grep -rn "import com\.gsim\.\(app\|commands\|interaction\|webui\)\." gsim-core/src
grep -rn "org\.thymeleaf\|org\.sqlite\|java\.sql\|org\.jsoup" gsim-core/src
grep -rn "GsimMcpServer\|McpStandaloneToolRegistry" gsim-core/src/main/java/com/gsim/mcp
```

第 1、2 条零命中；第 3 条仅 GsimMcpServer.java 自身。报告中列出移动文件数与拆边细节。

**验证（控制器）**：`mvn clean test --batch-mode` 必须 BUILD SUCCESS（gsim-app 首次带测试运行）。

---

### Task 4: gsim-agent 提取（运行时 + 业务工具 + MCP 组装 + 资源）

**Files:**
- Create: `gsim-agent/pom.xml`
- Create: `gsim-agent/src/main/java/com/gsim/agent/**`（自 gsim-core 整体 git mv，含 27 个类 + config/core/management/tool 子包）
- Create: `gsim-agent/src/main/java/com/gsim/agent/tools/search/{WikiSearchTool,MediaWikiSearchTool,LocalFileSearchService,StatusTool}.java`（改 package）
- Create: `gsim-agent/src/main/java/com/gsim/agent/tools/worldinfo/`（16 个类，改 package）
- Create: `gsim-agent/src/main/java/com/gsim/agent/tools/doc/`（9 个类，改 package）
- Create: `gsim-agent/src/main/java/com/gsim/agent/tools/importing/`（3 个类，改 package）
- Create: `gsim-agent/src/main/java/com/gsim/agent/tools/cache/`（3 个类，改 package）
- Create: `gsim-agent/src/main/java/com/gsim/agent/tools/ref/ResolveRefTool.java`、`gsim-agent/src/main/java/com/gsim/agent/tools/text/TextEditTool.java`（改 package）
- Create: `gsim-agent/src/main/java/com/gsim/agent/mcp/GsimMcpServer.java`（改 package）
- Create: gsim-agent 测试（25 个文件，见 Step 3）
- Create: `gsim-agent/src/main/resources/gsim/{agents,prompts,templates}/`（自 gsim-core 整体 git mv）
- Modify: 根 `pom.xml`、`gsim-core/pom.xml`、`gsim-app/pom.xml`
- Modify: gsim-core 剩余文件 / gsim-app / gsimap 中 import 被移动类的所有文件（sed，见 Step 4）

**Interfaces:**
- Consumes: gsim-core、gsim-agentlib、jackson、jline（CliAgentProgressSink）、log4j-core(test)
- Produces: Maven 坐标 `com.gsim:gsim-agent`；包 `com.gsim.agent.tools.{search,worldinfo,doc,importing,cache,ref,text}`、`com.gsim.agent.mcp`；T5 起 core 包改名会再次更新 agent 内 import

- [ ] **Step 1: 创建 gsim-agent/pom.xml**

```xml
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0" xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance" xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 http://maven.apache.org/xsd/maven-4.0.0.xsd">
    <modelVersion>4.0.0</modelVersion>

    <parent>
        <groupId>com.gsim</groupId>
        <artifactId>GSimulator</artifactId>
        <version>0.1.0-Alpha260723</version>
    </parent>

    <artifactId>gsim-agent</artifactId>
    <packaging>jar</packaging>

    <name>GSim Agent</name>
    <description>子 Agent 服务 — Agent 运行时（ToolLoop/Orchestrator/SubAgent）+ 全部工具实现 + MCP 组装 + core/map 工具桥接注册层</description>

    <dependencies>
        <dependency>
            <groupId>com.gsim</groupId>
            <artifactId>gsim-core</artifactId>
            <version>${project.version}</version>
        </dependency>
        <dependency>
            <groupId>com.gsim</groupId>
            <artifactId>gsim-agentlib</artifactId>
            <version>${project.version}</version>
        </dependency>
        <dependency>
            <groupId>com.fasterxml.jackson.core</groupId>
            <artifactId>jackson-databind</artifactId>
        </dependency>
        <dependency>
            <groupId>org.slf4j</groupId>
            <artifactId>slf4j-api</artifactId>
        </dependency>
        <dependency>
            <groupId>org.jline</groupId>
            <artifactId>jline</artifactId>
        </dependency>
        <dependency>
            <groupId>org.junit.jupiter</groupId>
            <artifactId>junit-jupiter</artifactId>
            <scope>test</scope>
        </dependency>
        <!-- 测试用（TestLogAppender 基于 log4j2） -->
        <dependency>
            <groupId>org.apache.logging.log4j</groupId>
            <artifactId>log4j-core</artifactId>
            <scope>test</scope>
        </dependency>
    </dependencies>

    <build>
        <plugins>
            <!-- SpotBugs: ratchet 模式（T9 生成 baseline） -->
            <plugin>
                <groupId>com.github.spotbugs</groupId>
                <artifactId>spotbugs-maven-plugin</artifactId>
                <configuration>
                    <effort>Max</effort>
                    <threshold>Medium</threshold>
                    <failOnError>false</failOnError>
                    <includeTests>false</includeTests>
                </configuration>
            </plugin>
        </plugins>
    </build>
</project>
```

根 `pom.xml`：`<modules>` 中 `<module>gsim-core</module>` 之后插入 `<module>gsim-agent</module>`。

- [ ] **Step 2: git mv 主代码（65 个文件）**

```bash
cd /home/cna/DevMosire/GSimulator
# 运行时（包名不变，整目录迁移）
git mv gsim-core/src/main/java/com/gsim/agent gsim-agent/src/main/java/com/gsim/agent
# 搜索/状态工具 4 个 → com.gsim.agent.tools.search
mkdir -p gsim-agent/src/main/java/com/gsim/agent/tools/search
for f in WikiSearchTool MediaWikiSearchTool LocalFileSearchService StatusTool; do
  git mv gsim-core/src/main/java/com/gsim/tool/$f.java gsim-agent/src/main/java/com/gsim/agent/tools/search/$f.java
done
# worldinfo 工具 16 个 → com.gsim.agent.tools.worldinfo
mkdir -p gsim-agent/src/main/java/com/gsim/agent/tools/worldinfo
git mv gsim-core/src/main/java/com/gsim/worldinfo/tool gsim-agent/src/main/java/com/gsim/agent/tools/worldinfo
# doc 工具 9 个 → com.gsim.agent.tools.doc
mkdir -p gsim-agent/src/main/java/com/gsim/agent/tools/doc
git mv gsim-core/src/main/java/com/gsim/doc/tool gsim-agent/src/main/java/com/gsim/agent/tools/doc
# importing 工具 3 个 → com.gsim.agent.tools.importing
mkdir -p gsim-agent/src/main/java/com/gsim/agent/tools/importing
git mv gsim-core/src/main/java/com/gsim/importing/tool gsim-agent/src/main/java/com/gsim/agent/tools/importing
# cache 工具 3 个 → com.gsim.agent.tools.cache
mkdir -p gsim-agent/src/main/java/com/gsim/agent/tools/cache
git mv gsim-core/src/main/java/com/gsim/cache/tool gsim-agent/src/main/java/com/gsim/agent/tools/cache
# ref / text 工具各 1 个
mkdir -p gsim-agent/src/main/java/com/gsim/agent/tools/ref gsim-agent/src/main/java/com/gsim/agent/tools/text
git mv gsim-core/src/main/java/com/gsim/ref/ResolveRefTool.java gsim-agent/src/main/java/com/gsim/agent/tools/ref/ResolveRefTool.java
git mv gsim-core/src/main/java/com/gsim/text/TextEditTool.java gsim-agent/src/main/java/com/gsim/agent/tools/text/TextEditTool.java
# MCP 组装
mkdir -p gsim-agent/src/main/java/com/gsim/agent/mcp
git mv gsim-core/src/main/java/com/gsim/mcp/GsimMcpServer.java gsim-agent/src/main/java/com/gsim/agent/mcp/GsimMcpServer.java
```

package 声明替换：

```bash
sed -i 's/^package com\.gsim\.tool;/package com.gsim.agent.tools.search;/' gsim-agent/src/main/java/com/gsim/agent/tools/search/*.java
sed -i 's/^package com\.gsim\.worldinfo\.tool;/package com.gsim.agent.tools.worldinfo;/' gsim-agent/src/main/java/com/gsim/agent/tools/worldinfo/*.java
sed -i 's/^package com\.gsim\.doc\.tool;/package com.gsim.agent.tools.doc;/' gsim-agent/src/main/java/com/gsim/agent/tools/doc/*.java
sed -i 's/^package com\.gsim\.importing\.tool;/package com.gsim.agent.tools.importing;/' gsim-agent/src/main/java/com/gsim/agent/tools/importing/*.java
sed -i 's/^package com\.gsim\.cache\.tool;/package com.gsim.agent.tools.cache;/' gsim-agent/src/main/java/com/gsim/agent/tools/cache/*.java
sed -i 's/^package com\.gsim\.ref;/package com.gsim.agent.tools.ref;/' gsim-agent/src/main/java/com/gsim/agent/tools/ref/*.java
sed -i 's/^package com\.gsim\.text;/package com.gsim.agent.tools.text;/' gsim-agent/src/main/java/com/gsim/agent/tools/text/*.java
sed -i 's/^package com\.gsim\.mcp;/package com.gsim.agent.mcp;/' gsim-agent/src/main/java/com/gsim/agent/mcp/*.java
```

移动后 `gsim-core` 中删除空目录：`gsim-core/src/main/java/com/gsim/tool/`（若已空）、`worldinfo/tool/`、`doc/tool/`、`importing/tool/`、`cache/tool/`（git mv 目录时自动消失；单文件移走后检查 `ref/`、`text/`、`mcp/` 目录是否为空——`ref/` 仍有 RefResolver.java、`text/` 仍有 TextEditor.java、`mcp/` 应为空目录，删空目录）。

- [ ] **Step 3: git mv 测试（25 个文件）**

```bash
cd /home/cna/DevMosire/GSimulator
# agent 运行时测试（包名不变，整目录含 agent/core、agent/tool 子包）
git mv gsim-core/src/test/java/com/gsim/agent gsim-agent/src/test/java/com/gsim/agent
# 其余测试改 package
mkdir -p gsim-agent/src/test/java/com/gsim/agent/tools/search
git mv gsim-core/src/test/java/com/gsim/tool/WikiSearchToolTest.java gsim-agent/src/test/java/com/gsim/agent/tools/search/WikiSearchToolTest.java
mkdir -p gsim-agent/src/test/java/com/gsim/agent/tools/worldinfo
git mv gsim-core/src/test/java/com/gsim/worldinfo/tool gsim-agent/src/test/java/com/gsim/agent/tools/worldinfo
mkdir -p gsim-agent/src/test/java/com/gsim/agent/mcp
git mv gsim-core/src/test/java/com/gsim/mcp/McpExternalLibTest.java gsim-agent/src/test/java/com/gsim/agent/mcp/McpExternalLibTest.java
git mv gsim-core/src/test/java/com/gsim/prompt gsim-agent/src/test/java/com/gsim/prompt
git mv gsim-core/src/test/java/com/gsim/root gsim-agent/src/test/java/com/gsim/root
```

测试 package 声明替换（agent 运行时测试不改）：

```bash
sed -i 's/^package com\.gsim\.tool;/package com.gsim.agent.tools.search;/' gsim-agent/src/test/java/com/gsim/agent/tools/search/*.java
sed -i 's/^package com\.gsim\.worldinfo\.tool;/package com.gsim.agent.tools.worldinfo;/' gsim-agent/src/test/java/com/gsim/agent/tools/worldinfo/*.java
sed -i 's/^package com\.gsim\.mcp;/package com.gsim.agent.mcp;/' gsim-agent/src/test/java/com/gsim/agent/mcp/*.java
```

移动后删除空目录：`gsim-core/src/test/java/com/gsim/tool/`、`gsim-core/src/test/java/com/gsim/mcp/`（若已空）。

`McpExternalLibTest.java` javadoc 中 "gsim-lib" 字样改为 "gsim-agentlib（经 gsim-agent）"。

- [ ] **Step 4: 全仓 import 重写（gsim-core 剩余 + gsim-app + gsimap + gsim-agent 自身）**

```bash
cd /home/cna/DevMosire/GSimulator
for d in gsim-core gsim-app gsimap gsim-agent; do
  find $d/src -name "*.java" -print0 | xargs -0 sed -i \
    -e 's/import com\.gsim\.worldinfo\.tool\./import com.gsim.agent.tools.worldinfo./g' \
    -e 's/import com\.gsim\.doc\.tool\./import com.gsim.agent.tools.doc./g' \
    -e 's/import com\.gsim\.importing\.tool\./import com.gsim.agent.tools.importing./g' \
    -e 's/import com\.gsim\.cache\.tool\./import com.gsim.agent.tools.cache./g' \
    -e 's/import com\.gsim\.tool\.WikiSearchTool;/import com.gsim.agent.tools.search.WikiSearchTool;/g' \
    -e 's/import com\.gsim\.tool\.MediaWikiSearchTool;/import com.gsim.agent.tools.search.MediaWikiSearchTool;/g' \
    -e 's/import com\.gsim\.tool\.LocalFileSearchService;/import com.gsim.agent.tools.search.LocalFileSearchService;/g' \
    -e 's/import com\.gsim\.tool\.StatusTool;/import com.gsim.agent.tools.search.StatusTool;/g' \
    -e 's/import com\.gsim\.ref\.ResolveRefTool;/import com.gsim.agent.tools.ref.ResolveRefTool;/g' \
    -e 's/import com\.gsim\.text\.TextEditTool;/import com.gsim.agent.tools.text.TextEditTool;/g' \
    -e 's/import com\.gsim\.mcp\.GsimMcpServer;/import com.gsim.agent.mcp.GsimMcpServer;/g'
done
```

FQN 使用点重写（GSimulatorApplication 内大量 `com.gsim.doc.tool.DocListTool` 式 FQN）：

```bash
for d in gsim-core gsim-app gsimap gsim-agent; do
  find $d/src -name "*.java" -print0 | xargs -0 sed -i \
    -e 's/com\.gsim\.worldinfo\.tool\./com.gsim.agent.tools.worldinfo./g' \
    -e 's/com\.gsim\.doc\.tool\./com.gsim.agent.tools.doc./g' \
    -e 's/com\.gsim\.importing\.tool\./com.gsim.agent.tools.importing./g' \
    -e 's/com\.gsim\.cache\.tool\./com.gsim.agent.tools.cache./g' \
    -e 's/com\.gsim\.ref\.ResolveRefTool/com.gsim.agent.tools.ref.ResolveRefTool/g' \
    -e 's/com\.gsim\.text\.TextEditTool/com.gsim.agent.tools.text.TextEditTool/g' \
    -e 's/com\.gsim\.mcp\.GsimMcpServer/com.gsim.agent.mcp.GsimMcpServer/g'
done
```

注意：移动后的工具类内部互相引用（如同包引用）与被移类对业务类的引用（如 `com.gsim.doc.DocStore`）**保持 com.gsim.* 不变**（T5 统一改 core 前缀）。移动文件内若有 `import com.gsim.agent.*`（原同模块不同包）在新模块下保留（agent 模块内自引）✓。

残留验证：

```bash
grep -rn "import com\.gsim\.\(worldinfo\.tool\|doc\.tool\|importing\.tool\|cache\.tool\)\." gsim-core/src gsim-app/src gsimap/src gsim-agent/src
grep -rn "import com\.gsim\.tool\.\(WikiSearchTool\|MediaWikiSearchTool\|LocalFileSearchService\|StatusTool\)" gsim-core/src gsim-app/src gsimap/src
grep -rn "com\.gsim\.mcp\.GsimMcpServer\|com\.gsim\.mcp\.McpStandaloneToolRegistry" gsim-core/src gsim-app/src gsimap/src
```

全部零命中。同步清理各文件因改名产生的未使用 import（对 `grep "^import"` 逐个文件核对删除）。

**应急说明**：若控制器编译时 agent 测试缺依赖（如 mockwebserver、jackson-datatype-jsr310），按编译错误在 gsim-agent/pom.xml 补 test 依赖并报告——不猜测、按错误信息补。

- [ ] **Step 5: 资源迁移**

```bash
cd /home/cna/DevMosire/GSimulator
git mv gsim-core/src/main/resources/gsim/agents gsim-agent/src/main/resources/gsim/agents
git mv gsim-core/src/main/resources/gsim/prompts gsim-agent/src/main/resources/gsim/prompts
git mv gsim-core/src/main/resources/gsim/templates gsim-agent/src/main/resources/gsim/templates
```

（`gsim-core/src/main/resources/gsim/` 迁空后删除该目录；`log4j2.xml` 留在 gsim-core resources 根。）

- [ ] **Step 6: pom 收尾**

1. `gsim-app/pom.xml`：加 `gsim-agent` 依赖（置于 gsim-core 之后）：

```xml
        <dependency>
            <groupId>com.gsim</groupId>
            <artifactId>gsim-agent</artifactId>
            <version>${project.version}</version>
        </dependency>
```

2. `gsim-core/pom.xml`：删除 gsim-agentlib 依赖（临时边拆除）与 jline 依赖（CliAgentProgressSink 已迁出）。

- [ ] **Step 7: 拆除验证与报告**

```bash
grep -rn "com\.gsim\.agentlib" gsim-core/src
grep -rn "org\.jline" gsim-core/src
grep -rln "import com\.gsim\.agent\." gsim-core/src/main/java | grep -v "/agent/"
```

全部零命中（第 3 条：gsim-core 内除 agent 包外无任何文件 import com.gsim.agent，因为 agent 包已整体迁出——若命中说明有遗漏）。

**验证（控制器）**：`mvn clean test --batch-mode` 必须 BUILD SUCCESS。注意新增 gsim-agent 模块的测试（约 25 个测试类）随构建运行。

---

### Task 5a: core 基础包改名（util/event/config/llm/skill/embedding/webimport）

**Files:**
- Modify: `gsim-core/src/main/java/com/gsim/{util,event,config,llm,skill,embedding,webimport}/**`（package 声明；util 的 JsonUtils 在 core 内改 `com.gsim.core.util`）
- Modify: `gsim-core/src/test/java/com/gsim/{util,event,config,llm,skill,embedding,webimport}/**`（llm 测试含 FakeLlmManager/RealApiStreamingTest/ProviderContextLengthDetectionTest）
- Modify: gsim-agent / gsim-map（此时仍叫 gsimap）/ gsim-app 内所有 import 这些包的源文件与测试（sed）
- Modify: `gsim-agentlib` 无（它不使用 core 包；如 grep 命中则报告）

**Interfaces:**
- Consumes: T4 模块现状
- Produces: `com.gsim.core.{util,event,config,llm,skill,embedding,webimport}`（JsonUtils core 副本就位，T6 起 map/agent/app 引 `com.gsim.core.util.JsonUtils`）

- [ ] **Step 1: gsim-core 内 package 声明替换**

```bash
cd /home/cna/DevMosire/GSimulator
for p in util event config llm skill embedding webimport; do
  find gsim-core/src -path "*/com/gsim/$p/*" -name "*.java" -print0 | xargs -0 sed -i "s/^package com\.gsim\.$p;/package com.gsim.core.$p;/"
done
```

（sed 的 `$p` 为字面循环变量展开；`gsim-core/src` 同时覆盖 main 与 test。）

- [ ] **Step 2: 全模块 import/FQN 重写（含 gsim-core 自身）**

```bash
cd /home/cna/DevMosire/GSimulator
for d in gsim-core gsim-agent gsimap gsim-app; do
  find $d/src -name "*.java" -print0 | xargs -0 sed -i \
    -e 's/com\.gsim\.util\./com.gsim.core.util./g' \
    -e 's/com\.gsim\.event\./com.gsim.core.event./g' \
    -e 's/com\.gsim\.config\./com.gsim.core.config./g' \
    -e 's/com\.gsim\.llm\./com.gsim.core.llm./g' \
    -e 's/com\.gsim\.skill\./com.gsim.core.skill./g' \
    -e 's/com\.gsim\.embedding\./com.gsim.core.embedding./g' \
    -e 's/com\.gsim\.webimport\./com.gsim.core.webimport./g'
done
```

**禁止对 `gsim-agentlib` 运行此 sed**（其 `com.gsim.agentlib.util.JsonUtils` 不能被 `com.gsim.core.util` 覆盖；agentlib 若引 core 包会是违规边，报告中 grep 确认零命中）。

- [ ] **Step 3: 残留检查与清理**

```bash
cd /home/cna/DevMosire/GSimulator
grep -rn "com\.gsim\.\(util\|event\|config\|llm\|skill\|embedding\|webimport\)\." gsim-core/src gsim-agent/src gsimap/src gsim-app/src | grep -v "com\.gsim\.core\."
grep -rn "import com\.gsim\.core" gsim-agentlib/src
```

第 1 条零命中（排除 `com.gsim.core.` 前缀后无残留）；第 2 条零命中（agentlib 不依赖 core）。同步清理未使用 import（`grep "^import"` 逐文件核对）。

**验证（控制器）**：`mvn clean test --batch-mode` 必须 BUILD SUCCESS。

---

### Task 5b: core 业务包改名（worldinfo/doc/session/importing/cache/compact/ref/text）

**Files:**
- Modify: `gsim-core/src/main/java/com/gsim/{worldinfo,doc,session,importing,cache,compact,ref,text}/**`（package 声明；worldinfo/loader、session 等子包同样覆盖）
- Modify: `gsim-core/src/test/java/com/gsim/{worldinfo,session,importing,cache,event...}/**`（仅剩的业务测试：KeywordIndexTest、worldinfo/loader 2 个、session 2 个、importing 7 个、cache/CacheStoreTest、webimport 测试等）
- Modify: gsim-agent / gsimap / gsim-app 内 import 这些包的文件（sed）

**Interfaces:**
- Consumes: T5a
- Produces: `com.gsim.core.{worldinfo,doc,session,importing,cache,compact,ref,text}` —— core 包改名全部完成

- [ ] **Step 1: gsim-core 内 package 声明替换**

```bash
cd /home/cna/DevMosire/GSimulator
for p in worldinfo doc session importing cache compact ref text; do
  find gsim-core/src -path "*/com/gsim/$p/*" -name "*.java" -print0 | xargs -0 sed -i "s/^package com\.gsim\.$p;/package com.gsim.core.$p;/"
done
```

- [ ] **Step 2: 全模块 import/FQN 重写**

```bash
cd /home/cna/DevMosire/GSimulator
for d in gsim-core gsim-agent gsimap gsim-app; do
  find $d/src -name "*.java" -print0 | xargs -0 sed -i \
    -e 's/com\.gsim\.worldinfo\./com.gsim.core.worldinfo./g' \
    -e 's/com\.gsim\.doc\./com.gsim.core.doc./g' \
    -e 's/com\.gsim\.session\./com.gsim.core.session./g' \
    -e 's/com\.gsim\.importing\./com.gsim.core.importing./g' \
    -e 's/com\.gsim\.cache\./com.gsim.core.cache./g' \
    -e 's/com\.gsim\.compact\./com.gsim.core.compact./g' \
    -e 's/com\.gsim\.ref\./com.gsim.core.ref./g' \
    -e 's/com\.gsim\.text\./com.gsim.core.text./g'
done
```

注意排除项：`com.gsim.agent.tools.*` 包路径不含上述前缀（tools 下的 worldinfo 等已是 agent 前缀）✓；`com.gsim.core.` 已是终态，sed 幂等 ✓。

- [ ] **Step 3: 残留检查与清理**

```bash
cd /home/cna/DevMosire/GSimulator
grep -rn "com\.gsim\.\(worldinfo\|doc\|session\|importing\|cache\|compact\|ref\|text\)\." gsim-core/src gsim-agent/src gsimap/src gsim-app/src | grep -v "com\.gsim\.core\."
grep -rn "^package com\.gsim\.\(worldinfo\|doc\|session\|importing\|cache\|compact\|ref\|text\);" gsim-core/src gsim-agent/src gsimap/src gsim-app/src
```

全部零命中。同步清理未使用 import。

**验证（控制器）**：`mvn clean test --batch-mode` 必须 BUILD SUCCESS。此后 spec 验收标准 4 中 core→agent 方向检查应已自然满足（T4 已拆）。

---

### Task 6a: gsimap → gsim-map（模块 + 包改名，工具暂留）

**Files:**
- Modify: 根 `pom.xml`、`gsim-map/pom.xml`（原 gsimap/pom.xml）、`gsim-app/pom.xml`
- Modify: `gsim-map/src/main/java/com/gsim/map/**`（原 com/gsimap/** 整树 git mv + package 替换，5 个包：config 1 / map 5 / service 14 / http 3 / tool 27）
- Modify: `gsim-map/src/test/java/com/gsim/map/service/TerrainCanvasTest.java`
- Modify: `gsim-app/src/main/java/com/gsim/Main.java`（gsimap import 更新）

**Interfaces:**
- Consumes: T5b 的 com.gsim.core.*（NodeLoader 等）；T1 的 gsim-agentlib（工具暂留 map 内，临时边）
- Produces: Maven 坐标 `com.gsim:gsim-map`；包 `com.gsim.map.{config,map,service,http,tool}`（tool 在 T6b 迁出）

- [ ] **Step 1: git mv + package 替换**

```bash
cd /home/cna/DevMosire/GSimulator
git mv gsimap gsim-map
git mv gsim-map/src/main/java/com/gsimap gsim-map/src/main/java/com/gsim/map
git mv gsim-map/src/test/java/com/gsimap gsim-map/src/test/java/com/gsim/map
find gsim-map/src -name "*.java" -print0 | xargs -0 sed -i \
  -e 's/^package com\.gsimap/package com.gsim.map/' \
  -e 's/import com\.gsimap/import com.gsim.map/g' \
  -e 's/com\.gsimap\./com.gsim.map./g'
```

- [ ] **Step 2: pom 编辑**

1. `gsim-map/pom.xml`：`<artifactId>gsimap</artifactId>` → `<artifactId>gsim-map</artifactId>`；`<name>GSimap</name>` → `<name>GSim Map</name>`；description 改为：`Hex 地图服务 — 挂载在 GSim 世界功能上的 Map 服务（地图数据、地形编辑、HTTP API、地图 UI）`；gsim-core 依赖（T2 已改）保持。
2. 根 `pom.xml`：`<module>gsimap</module>` → `<module>gsim-map</module>`
3. `gsim-app/pom.xml`：依赖 `<artifactId>gsimap</artifactId>` → `<artifactId>gsim-map</artifactId>`

- [ ] **Step 3: Main.java import 更新**

`gsim-app/src/main/java/com/gsim/Main.java` 三处（sed 已覆盖大部分，核对）：

```java
import com.gsim.map.http.GsimapHttpServer;
import com.gsim.map.service.MapService;
import com.gsim.map.tool.GsimapToolRegistrar;
```

- [ ] **Step 4: 残留检查**

```bash
grep -rn "gsimap" gsim-map/pom.xml gsim-app/pom.xml pom.xml gsim-map/src gsim-app/src gsim-core/src gsim-agent/src
grep -rn "com\.gsimap" gsim-map/src gsim-app/src
```

零命中（类名 GsimapHttpServer/GsimapToolRegistrar 等**类名保留**，仅包名改；grep 只查包名与 artifactId）。

**验证（控制器）**：`mvn clean test --batch-mode` 必须 BUILD SUCCESS。

---

### Task 6b: 地图工具迁入 gsim-agent（com.gsim.map.tool → com.gsim.agent.tools.map）

**Files:**
- Create: `gsim-agent/src/main/java/com/gsim/agent/tools/map/`（27 个类，自 gsim-map git mv + package 替换）
- Modify: `gsim-agent/pom.xml`（加 gsim-map 依赖）、`gsim-map/pom.xml`（删 gsim-agentlib 依赖）
- Modify: `gsim-app/src/main/java/com/gsim/Main.java`（GsimapToolRegistrar import）

**Interfaces:**
- Consumes: gsim-map（MapService/MapData 等）、gsim-core、gsim-agentlib（GsimRequestContext）
- Produces: `com.gsim.agent.tools.map.*`（含 GsimapToolRegistrar）；gsim-map 终态依赖仅 gsim-core + jackson

- [ ] **Step 1: git mv + package 替换**

```bash
cd /home/cna/DevMosire/GSimulator
mkdir -p gsim-agent/src/main/java/com/gsim/agent/tools
git mv gsim-map/src/main/java/com/gsim/map/tool gsim-agent/src/main/java/com/gsim/agent/tools/map
find gsim-agent/src/main/java/com/gsim/agent/tools/map -name "*.java" -print0 | xargs -0 sed -i \
  -e 's/^package com\.gsim\.map\.tool;/package com.gsim.agent.tools.map;/'
```

- [ ] **Step 2: 全模块 import 重写**

```bash
cd /home/cna/DevMosire/GSimulator
for d in gsim-core gsim-agent gsim-map gsim-app; do
  find $d/src -name "*.java" -print0 | xargs -0 sed -i \
    -e 's/import com\.gsim\.map\.tool\./import com.gsim.agent.tools.map./g' \
    -e 's/com\.gsim\.map\.tool\.GsimapToolRegistrar/com.gsim.agent.tools.map.GsimapToolRegistrar/g'
done
```

- [ ] **Step 3: pom 接线**

1. `gsim-agent/pom.xml`：加

```xml
        <dependency>
            <groupId>com.gsim</groupId>
            <artifactId>gsim-map</artifactId>
            <version>${project.version}</version>
        </dependency>
```

2. `gsim-map/pom.xml`：删除 gsim-agentlib 依赖（临时边拆除）。

- [ ] **Step 4: 拆除验证与报告**

```bash
grep -rn "com\.gsim\.agentlib\|com\.gsim\.agent\." gsim-map/src
grep -rn "import com\.gsim\.map\.tool" gsim-map/src gsim-agent/src gsim-app/src
```

全部零命中（地图工具的 GsimRequestContext 引用随工具迁出 map）。gsim-map 剩余 import 仅 com.gsim.map.* 与 com.gsim.core.*。

**验证（控制器）**：`mvn clean test --batch-mode` 必须 BUILD SUCCESS。

---

### Task 7: AgentBridge 收口（工具注册迁入 agent 桥接层）

**Files:**
- Create: `gsim-agent/src/main/java/com/gsim/agent/bridge/AgentBridge.java`
- Create: `gsim-agent/src/main/java/com/gsim/agent/bridge/CoreToolContext.java`
- Create: `gsim-agent/src/main/java/com/gsim/agent/bridge/WorldInfoToolContext.java`
- Modify: `gsim-app/src/main/java/com/gsim/app/GSimulatorApplication.java`（registerCoreTools/registerWorldInfoTools 瘦身为构造 + 桥接调用）
- Modify: `gsim-app/src/main/java/com/gsim/Main.java`（GsimapToolRegistrar 调用改走 AgentBridge）

**Interfaces:**
- Consumes: com.gsim.core.*（DocStore/DocCacheManager/ImportDocumentService/SkillIndex/EmbeddingClient/WorldInformation）、com.gsim.agentlib.tool.ToolRegistry、com.gsim.agentlib.mcp.GsimRequestContext、com.gsim.map.service.MapService、com.gsim.agent.tools.*
- Produces:
  - `record CoreToolContext(Path worldsDir, Path importDir, ImportDocumentService importDocService, DocStore docStore, DocCacheManager docCacheManager, SkillIndex docIndex, EmbeddingClient embeddingClient, Supplier<String> activeWorldId, AgentProgressSink progressSink)`
  - `record WorldInfoToolContext(Path worldsDir, WorldInformation worldInfo, Supplier<String> activeWorldId, DocCacheManager docCacheManager, Runnable onNodeChanged)`
  - `AgentBridge.registerCoreTools(ToolRegistry, CoreToolContext)`（注册 importing 3 + doc 9 + ref 1 + text 1 = 14 个工具）
  - `AgentBridge.registerWorldInfoTools(ToolRegistry, WorldInfoToolContext)`（注册 16 个 worldinfo 工具，含按 worldId 的 WorldInformation 缓存逻辑）
  - `AgentBridge.registerMapTools(ToolRegistry, MapService)`（委托 `GsimapToolRegistrar.registerAll`）

- [ ] **Step 1: 创建 CoreToolContext 与 WorldInfoToolContext**

`gsim-agent/src/main/java/com/gsim/agent/bridge/CoreToolContext.java`：

```java
package com.gsim.agent.bridge;

import com.gsim.agentlib.tool.AgentTool;
import com.gsim.core.doc.DocCacheManager;
import com.gsim.core.doc.DocStore;
import com.gsim.core.embedding.EmbeddingClient;
import com.gsim.core.event.AgentProgressSink;
import com.gsim.core.importing.ImportDocumentService;
import com.gsim.core.skill.SkillIndex;
import java.nio.file.Path;
import java.util.function.Supplier;

/**
 * 注册 core 业务工具所需的全部业务对象（由 gsim-app 组装后传入）。
 *
 * <p>gsim-core 只提供业务接口（DocStore、ImportDocumentService 等），
 * 工具包装（AgentTool 实现）全部在 gsim-agent 完成——本 record 是两者之间的桥。
 */
public record CoreToolContext(
        Path worldsDir,
        Path importDir,
        ImportDocumentService importDocService,
        DocStore docStore,
        DocCacheManager docCacheManager,
        SkillIndex docIndex,
        EmbeddingClient embeddingClient,
        Supplier<String> activeWorldId,
        AgentProgressSink progressSink) {}
```

`gsim-agent/src/main/java/com/gsim/agent/bridge/WorldInfoToolContext.java`：

```java
package com.gsim.agent.bridge;

import com.gsim.core.doc.DocCacheManager;
import com.gsim.core.worldinfo.WorldInformation;
import java.nio.file.Path;
import java.util.function.Supplier;

/**
 * 注册 worldinfo 工具的上下文。
 *
 * <p>{@code worldInfo} 可能为 null（Bootstrap 未产出时）；null 时
 * registerWorldInfoTools 仅注册 WorldList/WorldCreate（与现状行为一致）。
 */
public record WorldInfoToolContext(
        Path worldsDir,
        WorldInformation worldInfo,
        Supplier<String> activeWorldId,
        DocCacheManager docCacheManager,
        Runnable onNodeChanged) {}
```

- [ ] **Step 2: 创建 AgentBridge 并迁移注册代码**

`gsim-agent/src/main/java/com/gsim/agent/bridge/AgentBridge.java`。注册代码**逐行搬自** GSimulatorApplication 现状（T4 后路径 `gsim-app/src/main/java/com/gsim/app/GSimulatorApplication.java`）：

1. `registerCoreTools`：从 GSimulatorApplication.registerCoreTools 中搬 14 条 `toolRegistry.register(...)`（importing 3 + doc 9 + ref + text），对象来源改为 `ctx.importDocService()/ctx.docStore()/ctx.docCacheManager()/ctx.docIndex()/ctx.embeddingClient()/ctx.activeWorldId()/ctx.progressSink()`。不搬 DocStore 初始化、skills 迁移、agent-api-guide 创建等业务构造（留在 app）。
2. `registerWorldInfoTools`：搬 16 条注册 + `wiCache` ConcurrentHashMap 与 `wiSupplier` 逻辑（`com.gsim.agentlib.mcp.GsimRequestContext.worldId()` 直接可用——agent 已依赖 agentlib）。`worldInfo == null` 时只注册 WorldListTool/WorldCreateTool 并 log warn（原文保留）。
3. `registerMapTools`：

```java
    public static void registerMapTools(ToolRegistry registry, MapService mapService) {
        GsimapToolRegistrar.registerAll(registry, mapService);
    }
```

类 javadoc 说明桥接契约：外部程序复用 gsim-agent 时按 `AgentBridge.registerXxx` 组装。

- [ ] **Step 3: GSimulatorApplication 瘦身**

编辑 `gsim-app/src/main/java/com/gsim/app/GSimulatorApplication.java`：

1. 构造函数内两处调用点改为：

```java
        AgentBridge.registerCoreTools(toolRegistry, new CoreToolContext(
                worldsDir,
                config.getImportDir(),
                importDocService,
                docStore,
                docCacheManager,
                docIndex,
                embeddingClient,
                activeWorldId::get,
                compositeSink));

        AgentBridge.registerWorldInfoTools(toolRegistry, new WorldInfoToolContext(
                worldsDir, worldInfo, activeWorldId::get, docCacheManager, onNodeChanged));
```

（`importDocService/docStore/docCacheManager/docIndex/embeddingClient` 均在原 registerCoreTools 中构造，构造代码整体上移到构造函数。）

2. 删除 `private void registerCoreTools(...)` 与 `private void registerWorldInfoTools(...)` 方法体中的工具注册部分；方法本身删除（业务构造已上移），或改为上述桥接调用。以行为不变为准：迁移后启动流程 = 构造业务对象 → 桥接注册，工具实例化顺序与参数与现状完全一致。
3. `registerAgentTools` 保持现状（agent 模式组装：Orchestrator/permissionGate/sink 等依赖 app 装配，属 app 组装职责；计划内不改）。
4. import 清理：GSimulatorApplication 不再 import `com.gsim.agent.tools.{importing,doc,ref,text,worldinfo}` 任何类；新增 `com.gsim.agent.bridge.{AgentBridge,CoreToolContext,WorldInfoToolContext}`。

- [ ] **Step 4: Main.java 切换**

`gsim-app/src/main/java/com/gsim/Main.java`：

```java
import com.gsim.agent.bridge.AgentBridge;
// 删除 import com.gsim.agent.tools.map.GsimapToolRegistrar;
...
            AgentBridge.registerMapTools(toolRegistry, mapService);
```

- [ ] **Step 5: 验证与报告**

```bash
grep -rn "com\.gsim\.agent\.tools" gsim-app/src
grep -rn "registerCoreTools\|registerWorldInfoTools" gsim-app/src/main/java/com/gsim/app/GSimulatorApplication.java
```

第 1 条仅允许命中 `registerAgentTools` 使用的 `com.gsim.agent.tool.*`（agent 管理工具，属运行时装配）与 GSimulatorApplication 中 Orchestrator 相关 agent 运行时类；`tools.importing/doc/ref/text/worldinfo` 必须零命中。报告附行为不变论证（构造顺序、参数值对照）。

**验证（控制器）**：`mvn clean test --batch-mode` 必须 BUILD SUCCESS；并运行一次 `--no-cli` 冒烟（T10 后完整验收）。

---

### Task 8: 工具名残留清理（node_switch 等已删工具）

**Files（T4 后的路径）:**
- Modify: `gsim-agentlib/src/main/java/com/gsim/agentlib/mcp/ToolRegistryMcpAdapter.java`（:83 附近的 node_switch 硬编码列表）
- Modify: `gsim-agent/src/main/java/com/gsim/agent/ToolCategoryRegistry.java`（:50 `CATEGORIES.put("node_switch", ...)`）
- Modify: `gsim-agent/src/main/java/com/gsim/agent/ToolGroup.java`（NODE_MGMT 组 :41-43 的 description 与 `Set.of("node_list","node_status","node_create","node_switch","node_goto_parent")`）
- Modify: `gsim-agent/src/main/java/com/gsim/agent/ToolGroupManager.java`（grep 命中的同类残留）
- Modify: `gsim-agent/src/main/java/com/gsim/agent/tools/worldinfo/QueryElementTool.java`（:98 错误提示 "or node_switch to switch to an existing one"）
- Modify: `gsim-agent/src/main/java/com/gsim/agent/tools/ref/ResolveRefTool.java`、`gsim-agent/src/main/java/com/gsim/agent/tools/text/TextEditTool.java`（grep 命中的残留，按上下文处理）
- Modify: `gsim-agent/src/main/resources/gsim/prompts/orchestrator-world-state.md`（:33 node_mgmt 行）
- Modify: `gsim-agent/src/main/resources/gsim/prompts/orchestrator-system.md`（:31、:273、:288 工具表与用法示例）
- Modify: `gsim-agent/src/main/resources/gsim/agents/orchestrator/config.json`（staticSystemPrompt 内 Node 管理规则的工具表与"切换节点/返回父节点"小节）

**Interfaces:**
- Consumes: T4 后的文件位置
- Produces: 全仓零 node_switch / node_goto_parent / llm_call / agent_run 等已删工具名残留（工具名清单见 Step 1）

- [ ] **Step 1: 全量残留枚举**

```bash
cd /home/cna/DevMosire/GSimulator
grep -rn "node_switch\|node_goto_parent\|node_move\|node_delete\|node_edit\|world_switch\|agent_run\|agent_stop\|agent_status\|agent_list\|llm_call\|llm_test\|llm_list\|llm_status\|llm_models" gsim-agentlib/src gsim-core/src gsim-agent/src gsim-map/src gsim-app/src
```

对照 `git log`（Phase 1 删除的工具清单）逐条判定：引用**已删除工具名**的一律清理；引用**现存工具**（如 node_list/node_create/write_element）的保留。

- [ ] **Step 2: 代码残留清理**

1. `ToolCategoryRegistry.java`：删除 `CATEGORIES.put("node_switch", ...)` 行。
2. `ToolGroup.java` NODE_MGMT：description 删去 node_switch/node_goto_parent 两句；`Set.of` 改为 `Set.of("node_list", "node_status", "node_create")`。
3. `ToolGroupManager.java`：按 grep 命中位置同样清理。
4. `ToolRegistryMcpAdapter.java` :83：按其上下文语义处理——若为"已知 MUTATING 工具名"列表，将 node_switch 替换为现存 MUTATING 工具名（如 node_create）；若整段已无意义，删除该条目并保持方法行为不变。
5. `QueryElementTool.java` :98：提示语改为 `"Use node_create to create a new child node."`（删除 node_switch 分句）。
6. `ResolveRefTool.java` / `TextEditTool.java`：按命中上下文逐处修正（如提示语/注释中的工具名引用）。

- [ ] **Step 3: prompt 残留清理**

1. `orchestrator-world-state.md` :33：node_mgmt 行改为 `| node_mgmt | node_list, node_status, node_create | 节点管理 |`。
2. `orchestrator-system.md`：:273 工具表删除 node_switch / node_goto_parent 两行；:288 的 `node_switch nodeId=...` 调用示例与 :31 的提示语按现存工具改写（如"当前节点仅支持 node_create 创建子节点"）。
3. `orchestrator/config.json` 的 staticSystemPrompt：Node 管理规则小节的工具表与"切换节点"（node_switch）/"返回父节点"（node_goto_parent）两小节删除或改写，与现存 16 个 worldinfo 工具一致（node_list/node_status/node_create）。

- [ ] **Step 4: 复检与报告**

```bash
grep -rn "node_switch\|node_goto_parent\|node_move\|node_delete\|node_edit\|world_switch\|agent_run\|llm_call\|llm_test\|llm_status" gsim-agentlib/src gsim-core/src gsim-agent/src gsim-map/src gsim-app/src
```

零命中。报告列出每处修改的前后文。

**验证（控制器）**：`mvn clean test --batch-mode` 必须 BUILD SUCCESS（ToolGroup/ToolCategory 相关测试若断言了被删工具名会失败，fix loop 中同步修正测试断言——只删残留、不复活工具）。

---

### Task 9: 构建质量收尾（spotless + spotbugs baseline + 依赖方向核验）

**Files:**
- Modify: `gsim-core/pom.xml`、`gsim-agent/pom.xml`、`gsim-app/pom.xml`（spotbugs 注释与配置注释更新）
- Modify: `config/spotbugs/check-baseline.sh`（模块清单 gsim-lib → 新模块名）
- Delete: `config/spotbugs/gsim-lib-baseline.xml`
- Create: `config/spotbugs/{gsim-core,gsim-agent,gsim-app}-baseline.xml`（控制器生成，见 Step 2）
- Modify: 无 Java 代码（本任务代码改动为零；格式化与 baseline 由控制器执行）

**Interfaces:**
- Consumes: T8 后的全仓状态
- Produces: `mvn verify` 全绿所需的质量配置；spec 验收标准 2/3/6 的后半部分

- [ ] **Step 1: 控制器运行格式化**

```bash
mvn spotless:apply --batch-mode
```

修复 MapWebUIHandler（Phase 0-1 遗留格式违规）与迁移过程引入的一切格式问题。`git status` 列出被格式化文件，其中若出现纯格式化之外的意外改动，报告并人工核对。**由控制器 commit**（执行约定例外：格式化命令由控制器运行）。

- [ ] **Step 2: 控制器生成 spotbugs baseline**

```bash
mvn spotbugs:spotbugs -pl gsim-core,gsim-agent,gsim-app --batch-mode
cp gsim-core/target/spotbugsXml.xml config/spotbugs/gsim-core-baseline.xml
cp gsim-agent/target/spotbugsXml.xml config/spotbugs/gsim-agent-baseline.xml
cp gsim-app/target/spotbugsXml.xml config/spotbugs/gsim-app-baseline.xml
git rm config/spotbugs/gsim-lib-baseline.xml
```

同时（implementer）：`gsim-app/pom.xml` 的 spotbugs 配置 `failOnError>true` → `false`，注释改为 ratchet 模式说明（app 模块 T3 起承载 648 行 GSimulatorApplication 等迁移代码，改为与 core/agent 一致的 baseline 对比模式；零容忍留给 agentlib/map）。

`check-baseline.sh` 中模块名引用更新为 gsim-core/gsim-agent/gsim-app。gsim-agentlib 与 gsim-map 保持 `failOnError=true` 零容忍：控制器先跑 `mvn spotbugs:spotbugs -pl gsim-agentlib,gsim-map` 确认零 finding（若有 finding，交 implementer 修复后重跑——这两个模块代码少，修复量可控）。

pom 注释同步（implementer）：`gsim-core/pom.xml` 与 `gsim-agent/pom.xml` 的 ratchet 注释写明 baseline 文件位置与新 issue 数（从生成的 baseline XML 统计）。

- [ ] **Step 3: 依赖方向与依赖瘦身核验（控制器）**

```bash
mvn dependency:tree -pl gsim-agentlib,gsim-core,gsim-map,gsim-agent,gsim-app
grep -rn "com\.gsim\.agentlib\|com\.gsim\.agent\." gsim-core/src/main/java
grep -rn "com\.gsim\.agentlib\|com\.gsim\.agent\." gsim-map/src/main/java
grep -rn "com\.gsim\.map\." gsim-core/src/main/java
```

- gsim-core 依赖树只含 jackson/okhttp/slf4j/log4j2（无 gsim-* 兄弟模块）；后三条 grep 零命中
- gsim-map 依赖树只含 gsim-core + jackson
- gsim-agent 依赖树含 gsim-core + gsim-agentlib + gsim-map（无 gsim-app）
- gsim-app 依赖树含全部四个

**验证（控制器）**：`mvn clean verify --batch-mode` 必须 BUILD SUCCESS（spotless:check + checkstyle + spotbugs + 全测试）。spec 验收标准 2、3、4、6 至此全部满足。

---

### Task 10: 文档对齐（CLAUDE.md / DEVELOPMENT.md / 代码注释债务）

**Files:**
- Modify: `CLAUDE.md`（模块结构、Package 树、构建/运行命令、测试数与模块分布）
- Modify: `docs/DEVELOPMENT.md`（模块引用与运行方式）
- Modify: `gsim-app/src/main/java/com/gsim/webui/WebUiServer.java:20`（Javadoc 端口 8711 → 8710）
- Modify: `gsim-agent/src/main/java/com/gsim/agent/AgentConfig.java:19`（Javadoc "data/llms.json" → "llms.json（baseDir）"）

**Interfaces:**
- Consumes: T9 终态（模块名/包名/测试数）
- Produces: 文档与代码注释无 gsim-lib/gsimap 旧模块名、无 data/ 旧路径

- [ ] **Step 1: CLAUDE.md 更新**

1. 项目简介与模块说明：3 模块 → 5 模块（gsim-agentlib / gsim-core / gsim-agent / gsim-map / gsim-app），各一行职责说明（照 spec §1 目标架构图）；`gsim-lib`、`gsimap`、已删 gsim-agent 的所有提及统一替换。
2. Package 树：按新模块重写为 5 节；core 节列 `com.gsim.core.*` 13 个业务包；agent 节列运行时（`com.gsim.agent.*` 保留）+ `tools/{worldinfo,doc,importing,cache,search,ref,text,map}` + `mcp` + `bridge`；agentlib 节列 `tool`/`mcp`/`util`；app 节列 app/commands/interaction/webui；map 节列 config/map/service/http。
3. 运行命令节：jar 路径不变；模块构建命令改为 `mvn clean test`（根聚合）与 `mvn -pl gsim-core,gsim-agent,gsim-app -am` 示例。
4. 测试节：测试数按控制器最近一次 `mvn clean test` 输出更新，按模块分布列出（gsim-core / gsim-agent / gsim-agentlib / gsim-map / gsim-app）。
5. 依赖方向说明一节（新增，5-8 行）：禁令矩阵照 Global Constraints。

- [ ] **Step 2: docs/DEVELOPMENT.md 更新**

`gsim-lib`/`gsimap` 提及替换为新模块名；`target/GSimulator.jar` 运行方式改为 `java -jar gsim-app/target/gsim-app-0.1.0-Alpha260723.jar`；Phase 0-1 遗留的 `data/…` 路径表述按 T1.6 报告第四节清单修正（与 CLAUDE.md 同事实源）。

- [ ] **Step 3: 代码注释债务（Phase 0-1 遗留）

1. `WebUiServer.java:20` Javadoc：`端口 8711` → `端口 8710`（核对 WebUiConfig.defaults() 实际默认值）
2. `AgentConfig.java:19` Javadoc：`对应 data/llms.json` → `对应 llms.json（应用工作目录 baseDir）`
3. `gsim-app/src/main/java/com/gsim/Main.java` 类 javadoc：`--no-cli — MCP stdio + …` → `--no-cli — MCP HTTP(8720) + …`（与 Main 实际行为一致，T1.6 已在 CLAUDE.md 修正过同一事实）

- [ ] **Step 4: 残留检查与报告**

```bash
grep -rn "gsim-lib\|gsimap" CLAUDE.md docs/DEVELOPMENT.md
grep -rn "data/llms.json\|data/agents\|data/worlds" CLAUDE.md docs/DEVELOPMENT.md
```

零命中（历史说明注记若保留需有"历史"字样前缀，与 T1.6 风格一致）。

**验证（控制器）**：`mvn clean test --batch-mode` 必须 BUILD SUCCESS（文档任务回归确认）。

---

## 控制器最终验收（全部任务完成后，无需 implementer）

1. `mvn clean verify --batch-mode` BUILD SUCCESS（spotless:check + checkstyle + spotbugs + 全部测试）
2. 打包：`mvn package -pl gsim-app -am -DskipTests`，产出 `gsim-app/target/gsim-app-0.1.0-Alpha260723.jar`
3. 运行验收（临时目录执行，避免污染工作区）：
   - `java -jar gsim-app/target/gsim-app-0.1.0-Alpha260723.jar --no-cli`：确认 Web UI(8710)/Map UI(8711)/MCP HTTP(8720) 三服务日志就绪
   - `curl http://127.0.0.1:8720/mcp` 的 `tools/list` 请求返回全部工具（core 业务 30 + 地图 27 + agent 管理 13 + 搜索 4 ≈ 74 个工具，以实际为准，不得少于 T0 前基线）
   - `curl http://127.0.0.1:8710/`（Web UI 首页）与 `curl http://127.0.0.1:8711/`（Map UI）返回 200
   - 关停进程，`git status` 确认工作区除运行产物（worlds/caches/logs 等 gitignore 项）外干净
4. 依赖方向终验：T9 Step 3 的四条 grep + `mvn dependency:tree` 复跑
5. 全部通过后：SDD 终审（全分支代码审查）→ 修复轮次（如有）→ 使用 superpowers:finishing-a-development-branch 完成分支
