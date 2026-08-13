id: {{id}}
type: branch
name: {{name}}
parent: {{parent}}
turn: {{turn}}
world_time: {{world_time}}
status: resolved
tags: [时间节点, 推演记录, 上下文节点]
updated: {{updated}}
-------------------

# {{name}}

## 一、本节点输入

{{input}}

## 二、LLM 上下文记录

### user

{{llm_user}}

## 三、推演结果

{{result}}

## 四、世界观/设定增量（已废弃 — 请使用 knowledge_upsert 写入 embDB）

{{world_delta}}

## 五、实体状态增量（已废弃 — 请使用 knowledge_upsert 写入 embDB）

{{entity_delta}}

## 六、推演规则增量（已废弃 — 请使用 knowledge_upsert 写入 embDB）

{{rule_delta}}

## 七、交互逻辑增量（已废弃 — 请使用 knowledge_upsert 写入 embDB）

{{interaction_delta}}

## 八、未总结 Skill 增量（已废弃 — 请使用 knowledge_upsert 写入 embDB）

{{skill_delta}}

## 九、下节点风险

{{risks}}
