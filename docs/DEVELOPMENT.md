# GSimulator 开发指南

Java 21 + Maven 3.8+ 多模块项目开发设置与工作流。

## 1. 前置要求

- JDK 21+
- Maven 3.8+
- Git

## 2. 快速启动

```bash
git clone <repo-url>
cd GSimulator
mvn package -DskipTests
java -jar target/GSimulator.jar              # CLI 交互模式
java -jar target/GSimulator.jar --no-cli     # MCP stdio 模式
java -jar target/GSimulator.jar --cli --http # CLI + HTTP API
```

## 3. 首次配置

- 首次运行自动触发 **ConfigWizard**（交互式配置 LLM provider）
- `data/gsim.properties` 自动生成（API host/port、LLM 默认值、Agent 限制）
- `data/llms.json` 自动生成模板（需填入 API key）
- `data/agents/` 自动从 classpath fallback 复制 Agent 配置
- 环境变量：`LLM_BASE_URL`、`LLM_API_KEY`、`LLM_MODEL`、`LLM_TEMPERATURE`

## 4. data/ 目录结构

```
data/
├── gsim.properties              # 应用配置 (AppConfig)
├── llms.json                    # LLM provider 配置
├── agents/                      # Agent 配置
│   ├── orchestrator.json
│   ├── sim.json
│   └── search.json
├── worlds/                      # 世界数据
│   └── {worldId}/
│       ├── n0000.json           # 根节点
│       ├── n0001.json           # 回合节点
│       ├── nXXXX_map.json       # 地图快照 (每节点)
│       ├── state/               # 活跃状态
│       │   └── active.json
│       └── caches/              # 对话缓存
│           └── {sessionId}.json
├── docs/                        # DocStore 文档
│   └── {docId}.json
└── import/                      # 导入资料
```

## 5. 常用命令

| 命令 | 说明 |
|------|------|
| `mvn clean package -DskipTests` | 编译 + 打包 (跳过测试) |
| `mvn test` | 运行所有测试 |
| `mvn verify` | 完整质量检查 |
| `mvn verify -Dspotbugs.skip=true` | 快速验证 (跳过 SpotBugs) |
| `mvn spotless:apply` | 自动格式化代码 |
| `mvn test -pl gsimap` | 仅 gsimap 模块测试 |
| `java -jar target/GSimulator.jar` | CLI 模式启动 |
| `java -jar target/GSimulator.jar --no-cli` | MCP stdio 模式启动 |
| `rm -rf data/ && java -jar target/GSimulator.jar` | 清除数据从头启动 |

## 6. IDE 设置

- **IntelliJ IDEA**: 安装 palantirJavaFormat 插件
- Spotless 在 `process-sources` 阶段自动格式化，IDE 插件保持一致
- 项目 JDK 设置为 Java 21

## 7. 代码风格

- **Spotless** (palantirJavaFormat) 负责所有格式（缩进、括号、import 排序等）
- **Checkstyle** 保留语义规则（命名、Javadoc、代码规模：方法 ≤200 行、参数 ≤10 个）
- DTO 优先使用 Java record
- Prompt 不在 Java 代码中硬编码（放 `resources/gsim/prompts/*.md` 或 AgentConfig JSON）
- 所有环境变量读取统一走 `AppConfig`
- 业务代码不允许直接读写控制台（走 `InteractionManager` / `ConsoleInteractionAdapter`）

## 8. 模块开发指南

**开发 gsim-lib 核心功能：**
```bash
mvn test -pl gsim-lib                    # 运行 gsim-lib 测试
mvn spotbugs:spotbugs -pl gsim-lib       # 检查 SpotBugs
```

**开发 gsimap 地图功能：**
```bash
mvn test -pl gsimap                      # 运行 gsimap 测试
mvn verify -pl gsimap                    # gsimap 质量门禁（必须 0 SpotBugs）
# 地图前端 (Canvas JS) 位于 gsimap/src/main/resources/web/
# 启动后访问 http://127.0.0.1:8711
```

**开发 gsim-app CLI 入口：**
```bash
mvn package -DskipTests                  # 打包 fat JAR
java -jar target/GSimulator.jar
```

## 9. Prompt 开发

- Agent 系统提示词：`resources/gsim/agents/*/config.json` 的 `staticSystemPrompt` 字段
- Markdown prompt 文件：`resources/gsim/prompts/` 目录
- `prompts/` 根目录中的独立模板：`OrchestratorAgent_compress.md`、`SearchAgent_system.md`、`SimAgent_system.md`
- 变量替换：`{{variable}}` 在 `AgentConfig.renderUserPrompt()` 中处理

## 10. 分支/World/Node 概念澄清

- **Git 分支** — 代码版本控制
- **World** — 独立的世界观/剧本（`data/worlds/{worldId}/`）
- **Node** — 分支链上的回合/快照（`n0000` → `n0001` → ...）
- 在 CLI 中用 `node_create` 推进回合，用 `node_switch` 切换分支，**不要**切换 Git 分支来改变 World
- 使用 `world_switch` MCP 工具切换 World，使用 `world_create` 创建新 World

## 11. 禁止事项（来自 CLAUDE.md）

- ❌ 业务代码直接访问环境变量（走 AppConfig）
- ❌ 业务代码直接拼 HTTP 请求（走 LlmManager）
- ❌ GSimulatorApplication 中写业务逻辑（只做依赖注入和启动）
- ❌ 命令类中写复杂推演逻辑（走 Agent）
- ❌ Prompt 写死在 Java 代码中
- ❌ 吞异常
- ❌ 输出 API Key
- ❌ 测试依赖外部服务
- ❌ 静态全局可变状态

## 12. Cross-References

- `ARCHITECTURE.md` — 系统架构总览
- `TESTING.md` — 测试策略与质量门禁
- `DATA-MODEL.md` — 核心数据结构
- `CLAUDE.md`（项目根目录）— AI Agent 开发指南，含完整开发规则
