# GSimulator

一个基于 Java 21 + Maven 的多 Agent 推演工作流引擎，服务于文游 / 架空历史 / 玩家行动推演场景。

第一版提供 CLI REPL 模式和 HTTP API + SSE 流式事件输出，后续将扩展 Web UI。

## 快速开始

### 环境要求

- Java 21+
- Maven 3.8+

### 构建

```bash
mvn package
```

### 运行

```bash
java -jar target/GSimulator.jar
```

### 配置

复制 `.env.example` 为 `.env`，填入实际的 LLM API 配置：

```bash
cp .env.example .env
```

然后通过环境变量加载（或在启动前 export）。

## CLI 命令列表

| 命令 | 说明 |
|------|------|
| `/help` | 显示所有命令说明 |
| `/status` | 显示当前状态 |
| `/newturn` | 创建新回合 |
| `/player <玩家名> <行动内容>` | 登记玩家行动 |
| `/actions` | 显示当前回合玩家行动 |
| `/clearactions` | 清空当前回合未结算行动 |
| `/save` | 手动保存状态 |
| `/load <campaignId>` | 加载指定战役 |
| `/turn <turnId>` | 切换到指定回合 |
| `/import` | 从 import/ 导入本地文件 (stub) |
| `/import <URL>` | 抓取网页并生成 txt 到 import/web/ |
| `/import <URL> --fetch-only` | 只抓取网页生成 txt，不入库 |
| `/import <URL> --no-crawl` | 只抓取当前页面 |
| `/import <URL> --max-pages N` | 限制抓取页数 (默认 50) |
| `/import <URL> --depth N` | 限制递归深度 (默认 2) |
| `/import <URL> --delay-ms N` | 请求间隔毫秒 (默认 1000) |
| `/exit` | 退出 |

## 示例流程

```bash
$ java -jar target/GSimulator.jar

GSimulator started.
Current campaign: default-campaign
Current turn: turn-001

gsim> /player 张三 向北方边境派出三个侦察连，要求地方商会提供粮食
已记录玩家行动：张三 / turn-001

gsim> /player 李四 宣布封锁港口，禁止敌对势力商船进入
已记录玩家行动：李四 / turn-001

gsim> /import https://m.prts.wiki --max-pages 3 --depth 1 --delay-ms 1000 --fetch-only
=== Web 导入结果 ===
Pages fetched: 2
Files written: 2

gsim> /newturn
已创建新回合: turn-002

gsim> /exit
再见
```

## HTTP API

```bash
# 仅 HTTP API
java -jar target/GSimulator.jar --http

# CLI + HTTP API
java -jar target/GSimulator.jar --cli --http

# 查看状态
curl http://127.0.0.1:8710/api/status

# 执行命令
curl -X POST http://127.0.0.1:8710/api/command \
  -H "Content-Type: application/json" \
  -d '{"sessionId":"default","command":"/player 张三 向北方派出侦察队"}'

# SSE 流式命令
curl -N -X POST http://127.0.0.1:8710/api/command/stream \
  -H "Content-Type: application/json" \
  -d '{"sessionId":"default","command":"/status"}'
```

### SSE 事件类型

command_started, command_done, command_error, run_stage, tool_started, tool_done,
llm_delta, llm_reasoning_delta, llm_done, import_progress, search_progress, result, done

### API 端点

| 方法 | 路径 | 说明 |
|------|------|------|
| GET | /api/status | 应用状态 |
| POST | /api/command | 执行命令 |
| POST | /api/command/stream | SSE 流式命令 |
| GET/POST | /api/campaigns | Campaign 管理 |
| GET/POST | /api/campaigns/{id}/turns | Turn 管理 |
| GET/POST/DELETE | /api/campaigns/{id}/turns/{tid}/actions | PlayerAction CRUD |
| POST | /api/import/local | 本地导入 |
| POST | /api/import/url | URL 导入 |
| GET/POST | /api/searchdb | 知识库搜索（预留） |
| GET | /api/logs[/{taskId}] | 日志 |
| GET | /api/outputs[/{taskId}] | 输出文件 |
| GET/POST | /api/branches | 分支管理（预留） |

## 项目结构

```
src/main/java/com/gsim/
├── Main.java              # 程序入口
├── app/                   # 应用启动和配置
├── api/                   # HTTP API 层
│   ├── handlers/          # API handler 实现
│   └── dto/               # API DTO
├── event/                 # 统一事件系统（EventBus、SSE/Console sink）
├── interaction/           # 交互层（CLI REPL）
├── campaign/              # 战役/回合/玩家行动
├── agent/                 # LLM Agent (stub)
├── chroma/                # ChromaDB 客户端 (stub)
├── llm/                   # LLM 客户端封装（含流式接口）
├── prompt/                # Prompt 管理 (stub)
├── crawler/               # 联网爬虫接口
├── importdata/            # 资料导入管道 (stub)
├── webimport/             # 网页抓取管道 (fetch-only 可用)
├── task/                  # 任务上下文和日志 (stub)
├── timeline/              # 时间线 (stub)
├── world/                 # 世界状态 (stub)
├── storage/               # 持久化
├── output/                # 输出格式化
└── util/                  # 工具类
```

## 开发阶段

### ✅ 当前已完成

| 功能 | 说明 |
|------|------|
| CLI REPL | /help, /status, /exit + 交互循环 |
| Campaign / Turn / PlayerAction | /player, /actions, /clearactions, /save, /newturn, /load, /turn |
| /import URL fetch-only 网页抓取 | 抓取网页 → 提取正文 → 写入 import/web/{host}/{title}.txt |
| 普通网页抓取 | OkHttp + Jsoup，支持 BFS 爬取、速率限制、域名过滤 |
| MediaWiki 抓取骨架 | API 检测、parsed HTML 获取、curid/pageid/title 支持、命名空间排除 |
| HTML 正文提取 | 标题、段落、列表、表格、blockquote、pre；移除 script/style/nav/footer/header/aside/广告 |
| 安全边界 | sameHostOnly, maxPages=50, maxDepth=2, delayMillis=1000, maxBytes=5MB |
| 自动测试离线 | MockWebServer + fixture HTML + FakeChromaClient，115 个测试 0 失败 |
| CI | GitHub Actions (JDK 21, mvn test, mvn package) |

### 🔧 开发中

| 功能 | 说明 |
|------|------|
| ImportManager 入库 | 仍是 stub，文件暂存到 data/pending-imports/ |
| LocalKnowledgeStore | 替代 stub，实现真正的知识库写入 |
| /searchdb | 语义查询（依赖 ChromaDB 集成） |

### 📋 规划中

| Phase | 功能 |
|-------|------|
| PromptManager + LLM JSON | Prompt 外置管理和 LLM 调用封装 |
| ChromaDB 集成 | 向量数据库客户端和知识路由 |
| /run | Orchestrator 回合结算 |
| Agent workflow | 多 Agent 协作管道 |
| ResearchAgent | 联网搜索研究 |
| WriterAgent | 最终出文生成 |
| JS 渲染 | Playwright/Selenium (未来) |
| robots.txt | 解析与遵守 (未来) |

## 许可证

内部项目
