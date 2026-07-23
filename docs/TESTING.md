# GSimulator 测试策略

> Cross-References: [ARCHITECTURE.md](ARCHITECTURE.md) — 系统架构总览 | [DEVELOPMENT.md](DEVELOPMENT.md) — 开发环境与常用命令 | [CLAUDE.md](../CLAUDE.md) — 提交前检查清单

## 1. 测试概况

项目包含约 **80 个测试文件、~578 个测试用例**，分布在两个模块：

| 模块 | 路径 | 测试文件 | 覆盖范围 |
|------|------|---------|---------|
| **gsim-lib** | `src/test/java/com/gsim/` | ~75 | agent, api, cache, config, event, importing, interaction, llm, mcp, prompt, root, session, tool, util, webimport, worldinfo (19 个子包，镜像 main) |
| **gsimap** | `src/test/java/com/gsimap/service/` | ~5 | TerrainCanvasTest, CompressionValidator, MapDiff, MapResolver, ContourQueryEngine |
| **gsim-app** | 无独立测试 | — | 薄 CLI 入口，无业务逻辑 |

## 2. 离线优先原则 (Offline-First)

所有测试**不依赖外部 LLM 服务**：

- **FakeLlmManager** — 可配置 LLM 响应，测试 ToolLoop 行为（agent 包各测试）
- **MockWebServer** (OkHttp) — 模拟 HTTP 端点，覆盖 import / web 工具测试
- **Bootstrap 自动初始化** — 测试前自动创建 `data/` 目录结构

## 3. 测试命名约定

- **行为描述式：** `ToolLoopNoToolReminderMentionsFinishActionTest`
- **边界条件式：** `ImportDocumentReadOffsetBeyondEndReturnsEmptyTest`
- **中文适用：** `GsimapEdgeSetCreatesAndRemovesTagsTest`
- 所有测试使用 **JUnit 5 (Jupiter)**

## 4. 质量门禁 (Quality Gates)

| 门禁 | 工具 | 说明 |
|------|------|------|
| 编译 | `-Xlint:all,-processing,-options -Werror` | 零警告，全模块阻断 |
| 格式化 | Spotless (palantirJavaFormat) | 全模块阻断，`mvn spotless:apply` 自动修复 |
| 测试 | JUnit 5 + Maven Surefire | ~578 tests 全通过 |
| Bug 检测 | SpotBugs (Max+Medium) | gsimap/gsim-app 零 bugs；gsim-lib **棘轮基线** |
| 代码风格 | Checkstyle (语义级) | 仅报告，逐步清零 |

### SpotBugs 棘轮机制 (gsim-lib)

存量 **398 条**已基线化于 `config/spotbugs/gsim-lib-baseline.xml`。CI 通过指纹对比**阻止新增 bug**，同时允许存量修复。更新基线方式：

```bash
mvn spotbugs:spotbugs -pl gsim-lib
# 将 target/spotbugsXml.xml 复制到 config/spotbugs/gsim-lib-baseline.xml
```

### SpotBugs 分类修复优先级

| 优先级 | 分类 | 示例模式 |
|--------|------|---------|
| 🔴 High | 空指针、资源泄漏、同步错误 | `NP_*`, `OBL_*`, `IS2_*`, `EC_*` |
| 🟡 Medium | Random 只用一次、可变静态字段、无用对象 | `DMI_RANDOM_USED_ONLY_ONCE`, `MS_PKGPROTECT`, `UC_USELESS_OBJECT` |
| 🟢 Low | 可变内部状态暴露、未读字段 | `EI_EXPOSE_REP`, `URF_UNREAD_FIELD` |

### Checkstyle 配置说明

已移除与 Spotless 重叠的格式规则，保留语义级规则：命名约定、AvoidStarImport、Javadoc (public API)、EmptyStatement、EqualsHashCode、HiddenField、MissingSwitchDefault、MethodLength(200)、ParameterNumber(10)。

## 5. 常用运行命令

```bash
mvn test                          # 仅运行测试
mvn verify                        # 完整质量检查 (test + spotless + spotbugs + checkstyle)
mvn verify -Dspotbugs.skip=true   # 快速验证，跳过 SpotBugs
mvn spotless:apply                # 自动格式化
mvn test -pl gsimap               # 仅 gsimap 模块测试
```

## 6. 集成验收测试

项目内置集成验收测试 Skill (`.claude/skills/gsim-integration-test/SKILL.md`)，覆盖 8 个 Phase：

1. **代码质量门** — `mvn verify` 全通过
2. **MCP 连通性** — 5 个基础工具
3. **World & Node CRUD** — 节点生命周期
4. **GSimap 地图** — 13 个地图操作测试
4b. **Edge Pathway 边连通** — 8 个边操作测试
5. **WorldInfo 元素** — 12 个元素 CRUD 测试
6. **Doc CRUD** — 10 个文档操作测试
7. **SubAgent & 权限** — SubAgent 派发与权限门禁
8. **错误处理 & 边界** — 19 个边界条件测试

## 7. 提交前清理

- `data/` 目录下运行时文件不在版本控制中
- 测试残留 (`data/worlds/default/input.md`, `data/worlds/default/branches/`) 需 `git checkout` 或 `rm` 清理
- 首次或测试启动前 `rm -rf data/` 验证自动初始化
- 确认 `mvn clean verify` 全模块通过
