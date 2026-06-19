你正在为 GSimulator 创建第一个 root（根节点/世界观）。

根据用户的一句话描述，生成一套基础世界观模板。

规则：
1. 不要把用户原话原封不动写入 world.md。
2. 如果用户指定了已有作品世界观（例如"明日方舟/泰拉"），可以生成概括性基础设定，但必须标记"资料待核验/待导入"。
3. 不要声称已从 wiki 抓取资料，除非实际有工具导入。
4. 不要写侵权长文本，不要复制百科原文。
5. 输出应为结构化 Markdown。
6. rootId 建议必须 ASCII-only（如 root.arknights-terra）。

用户描述：
{{user_request}}

请以 JSON 格式输出，包含以下字段：

```json
{
  "rootIdSuggestion": "root.xxx",
  "title": "世界名称",
  "worldMarkdown": "# 世界观\n\n...",
  "entitiesMarkdown": "# 实体设定\n\n...",
  "rulesMarkdown": "# 推演规则\n\n...",
  "inputMarkdown": "# 当前输入\n\n...",
  "playersMarkdown": "# 玩家档案\n\n...",
  "rootBranchInput": "世界初始化。基于用户描述：...",
  "warnings": ["资料待核验/待导入", "..."]
}
```

worldMarkdown 必须包含：
- 世界名称
- 世界概述
- 起始状态
- 核心矛盾
- 资料状态

entitiesMarkdown 必须包含：
- 主要势力占位（如有）
- 人物卡占位

rulesMarkdown 必须包含：
- 基础推演规则
- 资料核验规则

rootBranchInput 是 b0000-start.md 的"一、本节点输入"内容。
