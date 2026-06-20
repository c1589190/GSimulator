---
name: generate-gpt-review-prompt
description: 每次改完代码后自动生成一份结构化 review prompt，可直接粘贴给 ChatGPT 等外部 LLM 进行代码核验。
category: review
trigger: manual
---

# GPT Review Prompt Generator

## 何时使用

- 完成一轮代码修改后（提交前或提交后）
- 需要外部 LLM（ChatGPT、Claude 网页版等）帮忙核验代码改动
- 用户说 "生成 review prompt"、"给 GPT 看看"、"生成核验提示词"

## 工作流程

### Step 1: 收集改动信息

```bash
# 当前分支和 HEAD
git branch --show-current
git log --oneline -3

# 改动文件列表和 diff 统计
git diff --stat HEAD~1   # 如果已提交
git diff --stat           # 如果未提交

# 实际 diff 内容
git diff HEAD~1           # 已提交
git diff                  # 未提交
```

### Step 2: 收集测试结果

```bash
# 如果有最近的测试输出
mvn test 2>&1 | tail -20
```

### Step 3: 收集项目上下文

- 读取 `CLAUDE.md` 的架构约束和禁止事项
- 读取关键被修改文件的结构

### Step 4: 生成 review prompt

生成一个 Markdown 文件，包含以下结构：

```markdown
# GSimulator 代码核验请求

## 项目背景
[简短说明：Java 21 Maven 项目，多 Agent 推演引擎，架构原则等]

## 本次改动概述
[一句话总结改了什么]

## 改动文件清单
[表格：文件 | 增/删行数 | 说明]

## 关键 Diff
[贴入关键 diff，按文件分段，标注重点区域]

## 架构约束（校验清单）
[从 CLAUDE.md 提取的关键约束，让 GPT 逐条核对]

## 测试结果
[贴入 mvn test 输出]

## 核验要点
1. 是否违反 CLAUDE.md 禁止事项？
2. 是否有遗漏的空指针风险？
3. 是否有并发安全问题？
4. 工具注册/注销是否成对？
5. Prompt 是否外置？
6. 新增代码是否符合项目命名和代码风格？
7. 测试是否覆盖了关键路径？

## 输出格式
请给出：
- 🔴 必须修（blocking issues）
- 🟡 建议修（non-blocking suggestions）
- 🟢 确认没问题（verified safe）
- 📋 总结建议
```

### Step 5: 直接打印到终端

**重要：将生成的 prompt 完整打印到终端（stdout），不要写入文件。** 用户需要直接看到内容并复制粘贴。

额外也写一份到 `.claude/gpt-review-prompt.md` 作为备份。

## 产出

- 终端直接输出完整核验 prompt（用户可直接复制）
- `.claude/gpt-review-prompt.md` — 备份文件
