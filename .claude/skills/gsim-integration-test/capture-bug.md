---
name: capture-bug
description: |
  实际 Bug 捕获与知识沉淀子流程。
  当集成测试、代码审查、或日常开发中发现真实 bug 时，
  将触发场景、修复过程、检验方法沉淀为结构化文档。
  触发词："记录这个 bug"、"沉淀 bug"、"capture bug"、"bug 归档"。
---

# Bug 捕获与知识沉淀

## 何时触发

- 集成测试中发现 FAIL，确认为真实 bug 并修复后
- SpotBugs / Checkstyle / 编译器 -Werror 报告的问题被修复后
- 用户说"记录这个 bug"、"沉淀到 Skill"

## 工作流程

### Step 1: 收集 bug 信息

```bash
# 当前分支和 HEAD
git branch --show-current
git log --oneline -3
```

从修复 commit 中提取：
- 哪个文件、哪个方法、哪个行号
- 什么条件下触发
- 修复前的错误行为
- 修复后的正确行为

### Step 2: 归类

按 bug 来源选择文件前缀：
- `spotbugs-*.md` — SpotBugs 发现
- `checkstyle-*.md` — Checkstyle 规则违反
- `logic-*.md` — 运行时逻辑错误
- `null-*.md` — 空指针 / 空状态
- `concurrency-*.md` — 并发 / 缓存一致性问题
- `api-*.md` — API / MCP 边界问题
- `build-*.md` — 编译 / 打包 / 依赖问题

### Step 3: 按模板写入

使用 `bugs/_template.md` 格式，写入 `bugs/` 文件夹。

命名：`{{YYYY-MM-DD}}-{{category}}-{{short-slug}}.md`

### Step 4: 确认沉淀质量

写入后自检：
- [ ] 触发场景：能否独立复现？
- [ ] 修复过程：是否写清了"为什么这样修"而非仅仅"改了什么"？
- [ ] 检验方法：是否有可执行的验证命令（mvn test / mvn verify / MCP 调用）？
- [ ] 关联文件：是否标注了涉及的源文件路径和行号？
