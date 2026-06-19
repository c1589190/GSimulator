# Empty-Data Bootstrap UX 修复 设计文档

## 目标

将 empty-data bootstrap 从"仅接受明确前缀"改为"接受任意自然语言"，同时通过 LLM 或 fallback 生成结构化根节点模板，不将原始用户文本直接写入 world.md。

## 设计决策

采用 **方案 A：LLM 优先 + deterministic fallback**。用户已在规格中明确要求此方向。

## 组件改动

### 1. BootstrapIntentParser 简化

**现状**：要求 8 种固定前缀之一。

**改为**：
```java
public record BootstrapIntent(
    boolean shouldBootstrap,
    String rawRequest,
    String sanitizedRequest,
    Optional<String> explicitTitle,
    Optional<String> explicitRootId
)

if dataRoot is strictly empty → any sanitized non-empty user text → ALLOW
else → no automatic bootstrap
```

当用户使用旧前缀格式（如 `初始化根节点：...`）时仍支持，用冒号后的内容作为 explicit 提示。

### 2. BootstrapWorldDraftGenerator 新增

核心新组件。职责：根据用户第一条消息生成结构化根节点初稿。

```java
record BootstrapWorldDraft(
    String rootIdSuggestion,
    String title,
    String worldMarkdown,
    String entitiesMarkdown,
    String rulesMarkdown,
    String inputMarkdown,
    String playersMarkdown,
    String rootBranchInput,
    List<String> warnings
)
```

**LLM 路径**：
- 通过 PromptResourceManager 加载 `gsim/prompts/bootstrap-world-draft.md`
- 调用 LlmClient.chat()
- 解析 LLM 返回的结构化 JSON/Markdown 到 BootstrapWorldDraft
- warnings 中包含"资料待核验/待导入"

**Fallback 路径**（LLM 不可用时）：
- 基于用户输入中的关键词做简单主题识别
- 生成包含"世界名称""初始设定""资料状态""待补全事项"的基础模板
- 所有内容标记为待补全

### 3. RootIdGenerator 扩展

新增主题识别方法 `suggestRootId(userText)`：
- 匹配已知作品/主题 → 生成语义化 rootId（如 `root.arknights-terra`）
- 冲突时追加短 hash
- 无法识别时 → `root.<hash8>`
- 保证 ASCII-only

### 4. DataManager.bootstrapFromEmpty 扩展

修改签名接受 `BootstrapWorldDraft`，写所有文件：
- world.md（来自 draft.worldMarkdown）
- entities.md（来自 draft.entitiesMarkdown）
- rules.md（来自 draft.rulesMarkdown）
- input.md（来自 draft.inputMarkdown）
- players.md（来自 draft.playersMarkdown）
- branches/b0000-start.md（来自 draft.rootBranchInput）

保持向后兼容：旧测试路径通过 `init()` 继续工作。

### 5. NodeAgentChatService.bootstrapFirstRoot 重写

新流程：
```
1. sanitize input
2. BootstrapIntentParser → BootstrapIntent
3. BootstrapWorldDraftGenerator.generate(intent) → BootstrapWorldDraft
4. DataManager.bootstrapFromEmpty(rootId, draft)
5. 初始化 root-scoped KnowledgeStore
6. 重建 ContextSession
7. 返回自然语言总结
```

### 6. orchestrator-system.md 更新

修改 "Empty-data bootstrap" 章节：
- 任意自然语言输入 → 允许 bootstrap
- 不得把原始用户消息直接作为 world.md
- 提到已有作品世界观时应生成概括性基础模板，标记资料待核验
- 不要声称已从 wiki 导入

### 7. 测试

新增 9 个测试类：
- EmptyDataAnyTextBootstrapsRootTest
- EmptyDataArknightsTextCreatesStructuredRootTest
- EmptyDataBootstrapDoesNotWriteRawUserTextOnlyTest
- EmptyDataBootstrapRootIdAsciiOnlyTest
- EmptyDataBootstrapUsesFallbackWhenLlmUnavailableTest
- EmptyDataBootstrapUsesLlmDraftWhenAvailableTest
- NonEmptyDataStillCannotAutoBootstrapTest
- BootstrapArknightsCreatesWorldEntitiesRulesPlayersTest
- BootstrapWarnsWorldNeedsVerificationTest

修改 2 个现有测试：
- BootstrapIntentAndFormatTest — wikiRequest/worldRequest 改为 SHOULD bootstrap（空 data 时）
- EmptyDataChatBootstrapTest — 适配新的 bootstrapFromEmpty 签名
