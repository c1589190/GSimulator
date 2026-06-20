id: skill.tool-policy
type: skill
name: 工具使用策略
scope: global
tags: [工具, 策略]
updated: 2026-06-18
-------------------

# 工具使用策略

## 适用范围
Agent 调用工具时的决策。

## 操作原则
- 优先用 data_search 查世界数据。
- 外部设定用 wiki_search。
- 不确定时两者都查。
- 最多 5 轮工具调用。