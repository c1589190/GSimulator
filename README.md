# GSimulator

一个基于 Java 21 的多 Agent 推演工作流引擎，服务于文游 / 架空历史 / 玩家行动推演场景。

## 功能概述

- **CLI REPL 交互式命令行**：启动后直接进入交互模式
- **玩家行动管理**：支持多名玩家同时递交行动
- **回合制推演**：主持人收集行动后，一键执行完整推演流程
- **知识库集成**：支持 ChromaDB 语义检索世界设定、规则、历史事件
- **多 Agent 协作**：Orchestrator 协调多个专业 LLM Agent 完成分析、推演、出文
- **联网研究**：必要时自动搜索和抓取外部资料
- **资料导入**：一键将 txt/md/json 资料导入知识库
- **完整审计**：每回合生成 Markdown 结果和 JSON TaskLog

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
| `/run [强制要求]` | 结算当前回合 |
| `/import` | 从 import/ 导入资料到知识库 |
| `/import <URL>` | 抓取网页并导入知识库 |
| `/import <URL> --fetch-only` | 只抓取网页生成 txt，不入库 |
| `/import <URL> --no-crawl` | 只抓取当前页面 |
| `/import <URL> --max-pages N` | 限制抓取页数 (默认 50) |
| `/import <URL> --depth N` | 限制递归深度 (默认 2) |
| `/import <URL> --delay-ms N` | 请求间隔毫秒 (默认 1000) |
| `/searchdb <查询内容>` | 语义查询知识库 |
| `/actions` | 显示当前回合玩家行动 |
| `/clearactions` | 清空当前回合未结算行动 |
| `/save` | 手动保存状态 |
| `/load <campaignId>` | 加载指定战役 |
| `/turn <turnId>` | 切换到指定回合 |
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

gsim> /run 本回合请重点考虑补给、地方商会态度、港口封锁的影响
开始结算 turn-001...

========== GSimulator 回合结算 ==========
...

gsim> /newturn
已创建新回合: turn-002

gsim> /exit
再见
```

## 项目结构

```
src/main/java/com/gsim/
├── Main.java              # 程序入口
├── app/                   # 应用启动和配置
├── interaction/           # 交互层（CLI REPL）
├── campaign/              # 战役/回合/玩家行动
├── agent/                 # LLM Agent
├── chroma/                # ChromaDB 客户端
├── llm/                   # LLM 客户端封装
├── prompt/                # Prompt 管理
├── crawler/               # 联网爬虫
├── importdata/            # 资料导入（本地 + URL）
├── webimport/             # 网页抓取管道
├── task/                  # 任务上下文和日志
├── timeline/              # 时间线
├── world/                 # 世界状态
├── storage/               # 持久化
├── output/                # 输出格式化
└── util/                  # 工具类
```

## 开发阶段

### ✅ 当前已完成

| Phase | 功能 | 状态 |
|-------|------|------|
| Phase 0 | 项目审计与计划 | ✅ |
| Phase 1 | 基础项目骨架 (Maven, Java 21) | ✅ |
| Phase 2 | 交互层 REPL (/help, /status, /exit) | ✅ |
| Phase 3 | Campaign / Turn / PlayerAction | ✅ |
| Phase 4 | PromptManager 与 LLM JSON 系统 | ✅ |
| Phase 5 | ChromaDB 与 /searchdb | ✅ |
| Phase 6 | /import (本地导入 + URL 网页导入) | ✅ |

Phase 6 详情：
- `/import`：扫描 `import/` 目录下的本地 txt/md/json 文件
- `/import <URL>`：抓取网页 → 提取正文 → 写入 `import/web/` → 入库
- 网页爬虫：支持普通网页 (OkHttp + Jsoup) 和 MediaWiki (API)
- `--fetch-only` / `--no-crawl` / `--max-pages` / `--depth` / `--delay-ms` 参数
- 自动测试不访问真实外网（MockWebServer + fixture HTML）
- 可手动验收：`/import https://m.prts.wiki --max-pages 3 --depth 1 --delay-ms 1000 --fetch-only`

### 🔧 开发中

- ImportManager 入库仍是 **stub**，文件暂存到 `data/pending-imports/`
- 下一阶段：LocalKnowledgeStore 替换 stub，实现真正的知识库写入

### 📋 规划中

| Phase | 功能 |
|-------|------|
| Phase 7 | Orchestrator 和 /run 骨架 |
| Phase 8 | 玩家行动分析、时间线、世界状态 |
| Phase 9 | ResearchAgent |
| Phase 10 | WriterAgent 和最终出文 |
| Phase 11 | 持久化、回放、Web UI 准备 |
| Future | JS 渲染 (Playwright/Selenium) |
| Future | robots.txt 解析与遵守 |
| Future | 分布式爬取 |

## 许可证

内部项目
