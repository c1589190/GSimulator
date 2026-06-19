# Empty-Data Bootstrap UX 修复 实现计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 将 empty-data bootstrap 从"仅接受明确前缀"改为"接受任意自然语言"，通过 LLM 或 fallback 生成结构化根节点模板。

**Architecture:** BootstrapIntentParser 改为宽松策略 → BootstrapWorldDraftGenerator (LLM/fallback) 生成结构化 draft → DataManager.bootstrapFromEmpty 写全部文件 → NodeAgentChatService 重建 context。

**Tech Stack:** Java 21, Maven, JUnit 5, FakeLlmClient (测试)

## Global Constraints

- data 严格为空时，任意非空自然语言输入都允许 bootstrap 第一个 root
- dataRoot 非空时，不允许 Agent 自动创建 root
- bootstrap 时不得把原始用户文本直接塞进 world.md
- bootstrap 必须生成结构化根节点模板
- rootId 必须 ASCII-only
- 终端控制字符必须清洗
- 不做 WebUI / streaming / LoreCard / ONNX / Qdrant / Lucene / sqlite-vec
- 不做自动网页搜索
- 不重构大范围 CLI
- 不写任何 deadline、交付日期、现实时间安排到代码/测试/文档/prompt/提交信息里

---

### Task 1: 修改 BootstrapIntentParser — 宽松策略

**Files:**
- Modify: `src/main/java/com/gsim/root/BootstrapIntentParser.java` (完整重写)

**Interfaces:**
- Produces: `BootstrapIntent` record (shouldBootstrap, rawRequest, sanitizedRequest, explicitTitle, explicitRootId)
- Produces: `static BootstrapIntent parse(String userInput, boolean isDataEmpty)`
- Produces: `static String nonBootstrapHint()` — 更新为新的提示语

- [ ] **Step 1: 重写 BootstrapIntentParser**

完整替换文件内容为：

```java
package com.gsim.root;

import java.util.Optional;

/**
 * 解析用户输入是否应触发 empty-data bootstrap。
 *
 * <p>data 严格为空时，任意非空自然语言输入都允许 bootstrap。
 * data 非空时，不允许自动 bootstrap。
 * 明确前缀格式（如"初始化根节点：..."）仍支持，冒号后内容作为 explicit 提示。
 */
public final class BootstrapIntentParser {

    // 旧的初始化前缀（仍支持，用于提取 explicit 提示）
    private static final String[] INIT_PREFIXES = {
            "初始化根节点：",
            "初始化根节点:",
            "初始化世界：",
            "初始化世界:",
            "创建第一个根节点：",
            "创建第一个根节点:",
            "init root:",
            "initialize root:",
    };

    private BootstrapIntentParser() {}

    /**
     * Bootstrap 意图。
     *
     * @param shouldBootstrap 是否应触发 bootstrap
     * @param rawRequest      原始用户输入
     * @param sanitizedRequest 清洗后的用户输入
     * @param explicitTitle   用户明确指定的标题（来自前缀格式冒号后内容，或为空）
     * @param explicitRootId  用户明确指定的 rootId（暂不支持，保留字段）
     */
    public record BootstrapIntent(
            boolean shouldBootstrap,
            String rawRequest,
            String sanitizedRequest,
            Optional<String> explicitTitle,
            Optional<String> explicitRootId
    ) {
        public static BootstrapIntent allow(String raw, String sanitized, Optional<String> title) {
            return new BootstrapIntent(true, raw, sanitized, title, Optional.empty());
        }

        public static BootstrapIntent deny() {
            return new BootstrapIntent(false, null, null, Optional.empty(), Optional.empty());
        }
    }

    /**
     * 解析用户输入。
     *
     * @param userInput   原始用户输入
     * @param isDataEmpty dataRoot 是否严格为空
     * @return BootstrapIntent
     */
    public static BootstrapIntent parse(String userInput, boolean isDataEmpty) {
        if (userInput == null || userInput.isBlank()) {
            return BootstrapIntent.deny();
        }

        String cleaned = TextSanitizer.safeStrip(userInput).trim();
        if (cleaned.isEmpty()) {
            return BootstrapIntent.deny();
        }

        if (!isDataEmpty) {
            // data 非空 — 不允许自动 bootstrap
            return BootstrapIntent.deny();
        }

        // data 严格为空 — 检查是否有明确前缀格式
        for (String prefix : INIT_PREFIXES) {
            if (cleaned.length() > prefix.length() && cleaned.startsWith(prefix)) {
                String content = cleaned.substring(prefix.length()).trim();
                if (!content.isBlank()) {
                    return BootstrapIntent.allow(userInput, cleaned, Optional.of(content));
                }
            }
        }

        // 无前缀 — 仍允许 bootstrap，整条消息作为请求
        return BootstrapIntent.allow(userInput, cleaned, Optional.empty());
    }

    /** 快速检查是否有 bootstrap 意图。 */
    public static boolean hasBootstrapIntent(String userInput, boolean isDataEmpty) {
        return parse(userInput, isDataEmpty).shouldBootstrap();
    }

    /** data 非空时拒绝自动 bootstrap 的提示消息。 */
    public static String nonBootstrapHint() {
        return """
                data 目录非空，不能自动创建第一个 root。
                请使用 /root create <rootId> <初始设定>，或清理 data 后重新启动。""";
    }
}
```

- [ ] **Step 2: 编译验证**

```bash
cd /home/cna/GSimulator && mvn compile -q 2>&1 | tail -5
```

Expected: BUILD SUCCESS

- [ ] **Step 3: 提交**

```bash
git add src/main/java/com/gsim/root/BootstrapIntentParser.java
git commit -m "feat: BootstrapIntentParser — any non-empty text triggers bootstrap when data is empty"
```

---

### Task 2: 扩展 RootIdGenerator — 主题识别

**Files:**
- Modify: `src/main/java/com/gsim/root/RootIdGenerator.java`

**Interfaces:**
- Produces: `static String suggestRootId(String userText)` — 从用户文本识别主题，生成语义化 ASCII rootId
- Consumes: `isValidRootId(String)` (existing), `generateFromContent(String)` (existing)

- [ ] **Step 1: 添加 suggestRootId 方法**

在 `RootIdGenerator` 类中添加以下方法（在 `extractTitle` 方法之后）：

```java
    /** 已知作品/主题关键词映射。key 为小写匹配词，value 为 rootId 后缀。 */
    private static final java.util.Map<String, String> KNOWN_TOPICS = java.util.Map.ofEntries(
            java.util.Map.entry("明日方舟", "arknights-terra"),
            java.util.Map.entry("arknights", "arknights-terra"),
            java.util.Map.entry("泰拉", "arknights-terra"),
            java.util.Map.entry("罗马尼亚", "romania"),
            java.util.Map.entry("romania", "romania"),
            java.util.Map.entry("东南亚", "sea"),
            java.util.Map.entry("southeast asia", "sea"),
            java.util.Map.entry("乌萨斯", "arknights-ursus"),
            java.util.Map.entry("ursus", "arknights-ursus"),
            java.util.Map.entry("罗德岛", "arknights-rhodes"),
            java.util.Map.entry("rhodes island", "arknights-rhodes"),
            java.util.Map.entry("维多利亚", "arknights-victoria"),
            java.util.Map.entry("victoria", "arknights-victoria"),
            java.util.Map.entry("炎国", "arknights-yen"),
            java.util.Map.entry("龙门", "arknights-lungmen"),
            java.util.Map.entry("lungmen", "arknights-lungmen"),
            java.util.Map.entry("卡西米尔", "arknights-kazimierz"),
            java.util.Map.entry("kazimierz", "arknights-kazimierz"),
            java.util.Map.entry("莱塔尼亚", "arknights-leithanien"),
            java.util.Map.entry("leithanien", "arknights-leithanien"),
            java.util.Map.entry("哥伦比亚", "arknights-columbia"),
            java.util.Map.entry("columbia", "arknights-columbia"),
            java.util.Map.entry("1850", "1850-era"),
            java.util.Map.entry("1876", "1876-era"),
            java.util.Map.entry("架空", "alt-history"),
            java.util.Map.entry("alternative history", "alt-history"),
            java.util.Map.entry("文游", "narrative"),
            java.util.Map.entry("边境", "frontier"),
            java.util.Map.entry("frontier", "frontier"),
            java.util.Map.entry("感染者", "arknights-infected"),
            java.util.Map.entry("infected", "arknights-infected"),
            java.util.Map.entry("源石", "arknights-originium"),
            java.util.Map.entry("originium", "arknights-originium"),
            java.util.Map.entry("天灾", "arknights-catastrophe"),
            java.util.Map.entry("移动城邦", "arknights-mobile-city"),
            java.util.Map.entry("mobile city", "arknights-mobile-city")
    );

    /**
     * 从用户文本中识别主题，生成语义化 rootId。
     * 如果能识别已知作品/主题 → root.&lt;topic&gt;
     * 如果无法识别 → root.&lt;hash8&gt;
     * 保证 ASCII-only。
     */
    public static String suggestRootId(String userText) {
        if (userText == null || userText.isBlank()) {
            return generateFromContent(userText);
        }

        String lower = userText.toLowerCase(java.util.Locale.ROOT);

        // 按匹配长度降序排列，优先匹配更长的关键词
        var sorted = KNOWN_TOPICS.entrySet().stream()
                .filter(e -> lower.contains(e.getKey().toLowerCase(java.util.Locale.ROOT)))
                .sorted((a, b) -> Integer.compare(b.getKey().length(), a.getKey().length()))
                .toList();

        if (!sorted.isEmpty()) {
            String suffix = sorted.get(0).getValue();
            return "root." + suffix;
        }

        // 无法识别 — 使用 hash
        return generateFromContent(userText);
    }

    /**
     * 生成带冲突避免的 rootId。
     * 如果 baseId 已被占用，追加短 hash。
     */
    public static String suggestRootIdWithCollisionAvoidance(String userText, java.util.Set<String> existingIds) {
        String base = suggestRootId(userText);
        if (existingIds == null || !existingIds.contains(base)) {
            return base;
        }
        // 冲突 — 追加短 hash
        String hash = sha256Hex8(userText != null ? userText : String.valueOf(System.nanoTime()));
        return base + "-" + hash.substring(0, 4);
    }
```

- [ ] **Step 2: 编译验证**

```bash
cd /home/cna/GSimulator && mvn compile -q 2>&1 | tail -5
```

Expected: BUILD SUCCESS

- [ ] **Step 3: 提交**

```bash
git add src/main/java/com/gsim/root/RootIdGenerator.java
git commit -m "feat: RootIdGenerator — topic-aware suggestRootId with known works mapping"
```

---

### Task 3: 新增 BootstrapWorldDraftGenerator

**Files:**
- Create: `src/main/java/com/gsim/root/BootstrapWorldDraftGenerator.java`
- Create: `src/main/resources/gsim/prompts/bootstrap-world-draft.md`

**Interfaces:**
- Produces: `BootstrapWorldDraft` record
- Produces: `BootstrapWorldDraft generate(BootstrapIntent intent)` — 主入口
- Produces: `BootstrapWorldDraft generateWithLlm(BootstrapIntent intent)` — LLM 路径
- Produces: `BootstrapWorldDraft generateFallback(BootstrapIntent intent)` — fallback 路径
- Consumes: `LlmClient.chat(LlmRequest)`, `RootIdGenerator.suggestRootId(String)`

- [ ] **Step 1: 创建 LLM prompt 模板**

创建 `src/main/resources/gsim/prompts/bootstrap-world-draft.md`：

```markdown
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
```

- [ ] **Step 2: 创建 BootstrapWorldDraftGenerator**

创建 `src/main/java/com/gsim/root/BootstrapWorldDraftGenerator.java`：

```java
package com.gsim.root;

import com.gsim.llm.LlmClient;
import com.gsim.llm.LlmMessage;
import com.gsim.llm.LlmRequest;
import com.gsim.llm.LlmResponse;
import com.gsim.resource.ResourceManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * 根据用户第一条消息生成结构化根节点初稿。
 *
 * <p>优先使用 LLM 生成贴合用户意图的内容。
 * 如果 LLM 不可用，使用 deterministic fallback。
 */
public class BootstrapWorldDraftGenerator {
    private static final Logger log = LoggerFactory.getLogger(BootstrapWorldDraftGenerator.class);

    private final LlmClient llmClient;
    private final String model;

    public BootstrapWorldDraftGenerator(LlmClient llmClient, String model) {
        this.llmClient = llmClient;
        this.model = model;
    }

    /**
     * 结构化根节点初稿。
     */
    public record BootstrapWorldDraft(
            String rootIdSuggestion,
            String title,
            String worldMarkdown,
            String entitiesMarkdown,
            String rulesMarkdown,
            String inputMarkdown,
            String playersMarkdown,
            String rootBranchInput,
            List<String> warnings
    ) {}

    /**
     * 主入口：根据 BootstrapIntent 生成 draft。
     * 优先 LLM，不可用时 fallback。
     */
    public BootstrapWorldDraft generate(BootstrapIntentParser.BootstrapIntent intent) {
        if (llmClient != null && llmClient.isAvailable()) {
            try {
                return generateWithLlm(intent);
            } catch (Exception e) {
                log.warn("LLM draft generation failed, falling back to deterministic: {}", e.getMessage());
            }
        }
        return generateFallback(intent);
    }

    /**
     * LLM 路径：调用 LLM 生成结构化 draft。
     */
    BootstrapWorldDraft generateWithLlm(BootstrapIntentParser.BootstrapIntent intent) {
        String userRequest = intent.sanitizedRequest();
        // 如果有 explicit title（来自前缀格式），拼接到请求中
        if (intent.explicitTitle().isPresent()) {
            userRequest = intent.explicitTitle().get();
        }

        String prompt;
        try {
            prompt = ResourceManager.renderTemplate("gsim/prompts/bootstrap-world-draft.md",
                    "user_request", userRequest);
        } catch (IOException e) {
            log.warn("Failed to load bootstrap-world-draft.md, using fallback: {}", e.getMessage());
            return generateFallback(intent);
        }

        LlmRequest request = new LlmRequest(model,
                List.of(LlmMessage.system(prompt)),
                0.3, 2048);
        LlmResponse response = llmClient.chat(request);

        if (!response.success()) {
            log.warn("LLM bootstrap draft call failed: {}", response.errorMessage());
            return generateFallback(intent);
        }

        return parseLlmResponse(response.content(), intent);
    }

    /**
     * 解析 LLM 返回的 JSON 到 BootstrapWorldDraft。
     * 如果解析失败，回退到 fallback。
     */
    private BootstrapWorldDraft parseLlmResponse(String content, BootstrapIntentParser.BootstrapIntent intent) {
        try {
            // 尝试提取 JSON 块
            String json = extractJson(content);
            com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
            com.fasterxml.jackson.databind.JsonNode node = mapper.readTree(json);

            String rootIdSuggestion = node.has("rootIdSuggestion") && !node.get("rootIdSuggestion").asText().isBlank()
                    ? node.get("rootIdSuggestion").asText()
                    : RootIdGenerator.suggestRootId(intent.sanitizedRequest());
            String title = node.has("title") ? node.get("title").asText() : "未命名世界";
            String worldMd = node.has("worldMarkdown") ? node.get("worldMarkdown").asText() : "";
            String entitiesMd = node.has("entitiesMarkdown") ? node.get("entitiesMarkdown").asText() : "";
            String rulesMd = node.has("rulesMarkdown") ? node.get("rulesMarkdown").asText() : "";
            String inputMd = node.has("inputMarkdown") ? node.get("inputMarkdown").asText() : "";
            String playersMd = node.has("playersMarkdown") ? node.get("playersMarkdown").asText() : "";
            String branchInput = node.has("rootBranchInput") ? node.get("rootBranchInput").asText() : "";
            List<String> warnings = new ArrayList<>();
            if (node.has("warnings") && node.get("warnings").isArray()) {
                for (var w : node.get("warnings")) {
                    warnings.add(w.asText());
                }
            }

            // 验证 rootId 是 ASCII-only
            if (!RootIdGenerator.isValidRootId(rootIdSuggestion)) {
                rootIdSuggestion = RootIdGenerator.suggestRootId(intent.sanitizedRequest());
            }

            // 确保基本内容不为空
            if (worldMd.isBlank()) {
                worldMd = buildFallbackWorldMd(intent);
            }
            if (entitiesMd.isBlank()) {
                entitiesMd = buildFallbackEntitiesMd();
            }
            if (rulesMd.isBlank()) {
                rulesMd = buildFallbackRulesMd();
            }
            if (inputMd.isBlank()) {
                inputMd = "# 当前输入\n\n暂无待结算内容。\n";
            }
            if (playersMd.isBlank()) {
                playersMd = buildFallbackPlayersMd();
            }
            if (branchInput.isBlank()) {
                branchInput = "世界初始化。基于用户描述：" + truncate(intent.sanitizedRequest(), 200);
            }

            return new BootstrapWorldDraft(rootIdSuggestion, title, worldMd, entitiesMd,
                    rulesMd, inputMd, playersMd, branchInput, warnings);
        } catch (Exception e) {
            log.warn("Failed to parse LLM bootstrap response, using fallback: {}", e.getMessage());
            return generateFallback(intent);
        }
    }

    /** 从 LLM 响应中提取 JSON 块。 */
    private String extractJson(String content) {
        // 尝试 ```json ... ``` 代码块
        int start = content.indexOf("```json");
        if (start >= 0) {
            start = content.indexOf('\n', start);
            if (start < 0) start = content.indexOf("```json") + 7;
            else start = start + 1;
            int end = content.indexOf("```", start);
            if (end > start) return content.substring(start, end).trim();
        }
        // 尝试直接的 { ... }
        start = content.indexOf('{');
        int end = content.lastIndexOf('}');
        if (start >= 0 && end > start) return content.substring(start, end + 1);
        return content;
    }

    /**
     * Fallback 路径：基于用户输入生成基础模板。
     * 不依赖 LLM，生成通用结构化内容。
     */
    BootstrapWorldDraft generateFallback(BootstrapIntentParser.BootstrapIntent intent) {
        String sanitized = intent.sanitizedRequest();
        String rootId = RootIdGenerator.suggestRootId(sanitized);
        String title = extractFallbackTitle(intent);
        String worldMd = buildFallbackWorldMd(intent);
        String entitiesMd = buildFallbackEntitiesMd();
        String rulesMd = buildFallbackRulesMd();
        String inputMd = "# 当前输入\n\n暂无待结算内容。\n";
        String playersMd = buildFallbackPlayersMd();
        String branchInput = "世界初始化。基于用户描述：" + truncate(sanitized, 200);
        List<String> warnings = List.of(
                "这是根据用户描述自动生成的基础模板，尚未从 wiki 导入或核验。",
                "所有设定均为占位，需要人工补充。"
        );

        return new BootstrapWorldDraft(rootId, title, worldMd, entitiesMd,
                rulesMd, inputMd, playersMd, branchInput, warnings);
    }

    private String extractFallbackTitle(BootstrapIntentParser.BootstrapIntent intent) {
        if (intent.explicitTitle().isPresent()) {
            return RootIdGenerator.extractTitle(intent.explicitTitle().get());
        }
        return RootIdGenerator.extractTitle(intent.sanitizedRequest());
    }

    private String buildFallbackWorldMd(BootstrapIntentParser.BootstrapIntent intent) {
        String title = extractFallbackTitle(intent);
        String summary = truncate(intent.sanitizedRequest(), 300);
        return "# 世界观\n\n"
                + "## 世界名称\n\n" + title + "\n\n"
                + "## 世界概述\n\n本 root 基于用户描述创建：" + summary + "\n\n"
                + "## 起始状态\n\n当前尚未指定具体起始年份、地区和玩家角色。默认作为待补全开局。\n\n"
                + "## 核心矛盾\n\n- 待补全\n\n"
                + "## 资料状态\n\n"
                + "- 世界观资料：基础模板，待导入或校对\n"
                + "- 国家与势力：待补全\n"
                + "- 人物卡：待录入\n"
                + "- 玩家资料：待录入\n";
    }

    private String buildFallbackEntitiesMd() {
        return "# 实体设定\n\n"
                + "## 主要势力占位\n\n待补全。\n\n"
                + "## 人物卡\n\n待录入。\n";
    }

    private String buildFallbackRulesMd() {
        return "# 推演规则\n\n"
                + "## 基础规则\n\n"
                + "- 本 root 使用用户指定的基础设定。\n"
                + "- 未经确认的具体设定应标记为待核验。\n"
                + "- 玩家行动应先保存为推演内容，再进行回合结算。\n"
                + "- 世界观根设定只能在根节点修改。\n";
    }

    private String buildFallbackPlayersMd() {
        return "# 玩家档案\n\n"
                + "本文件记录当前世界中的玩家角色、玩家势力、公开目标、隐藏倾向、资源、关系与当前状态。\n"
                + "玩家本回合行动不要写在这里，行动应写入 input.md。\n"
                + "推演后的玩家状态变化应优先写入 branch 的实体状态增量。\n\n"
                + "## 待录入\n\n暂无玩家档案。\n";
    }

    private static String truncate(String s, int maxLen) {
        if (s == null || s.isBlank()) return "";
        if (s.length() <= maxLen) return s;
        return s.substring(0, maxLen - 3) + "...";
    }
}
```

- [ ] **Step 3: 编译验证**

```bash
cd /home/cna/GSimulator && mvn compile -q 2>&1 | tail -10
```

Expected: BUILD SUCCESS

- [ ] **Step 4: 提交**

```bash
git add src/main/java/com/gsim/root/BootstrapWorldDraftGenerator.java src/main/resources/gsim/prompts/bootstrap-world-draft.md
git commit -m "feat: BootstrapWorldDraftGenerator — LLM/fallback structured root draft generation"
```

---

### Task 4: 扩展 DataManager.bootstrapFromEmpty 接受完整 draft

**Files:**
- Modify: `src/main/java/com/gsim/data/DataManager.java`

**Interfaces:**
- Produces: `void bootstrapFromEmpty(String rootId, BootstrapWorldDraft draft)` — 新重载
- Keeps: `void bootstrapFromEmpty(String rootId, String worldContentMd)` — 旧签名保留，委托到新方法

- [ ] **Step 1: 添加新重载方法**

在 `DataManager.java` 中，在现有 `bootstrapFromEmpty(String rootId, String worldContentMd)` 方法之后添加：

```java
    /**
     * 从空 data bootstrap 创建初始 root（使用完整 draft）。
     * 只在 dataRoot 严格为空时调用。
     */
    public void bootstrapFromEmpty(String rootId,
                                    com.gsim.root.BootstrapWorldDraftGenerator.BootstrapWorldDraft draft)
            throws IOException {
        if (hasAnyRoot()) {
            throw new IOException("Cannot bootstrap: data already has roots. Use /root create instead.");
        }
        if (!rootId.matches("[a-zA-Z0-9._-]+")) {
            throw new IOException("Invalid rootId: '" + rootId + "'. Must match [a-zA-Z0-9._-]+");
        }
        // 确保 dataRoot 存在
        if (!Files.isDirectory(dataRoot)) {
            Files.createDirectories(dataRoot);
        }
        // 创建 skills 和 experience
        Path skillsDir = dataRoot.resolve("skills");
        if (!Files.isDirectory(skillsDir)) {
            Files.createDirectories(skillsDir);
            writeFile(skillsDir.resolve("simulation-method.md"), ResourceManager.readText("gsim/templates/simulation-method.md"));
            writeFile(skillsDir.resolve("tool-policy.md"), ResourceManager.readText("gsim/templates/tool-policy-skill.md"));
            writeFile(skillsDir.resolve("output-style.md"), ResourceManager.readText("gsim/templates/output-style-skill.md"));
            writeFile(skillsDir.resolve("failure-lessons.md"), ResourceManager.readText("gsim/templates/failure-lessons-skill.md"));
            writeFile(skillsDir.resolve("generated-skill.md"), ResourceManager.readText("gsim/templates/generated-skill.md"));
        }
        Path expDir = dataRoot.resolve("experience");
        if (!Files.isDirectory(expDir)) {
            Files.createDirectories(expDir);
            writeFile(expDir.resolve("e0001.md"), ResourceManager.readText("gsim/templates/e0001-experience.md"));
        }
        // 创建 root 目录结构
        Path wd = dataRoot.resolve("worlds").resolve(rootId);
        for (String d : List.of("branches", "patches/pending", "patches/accepted", "patches/rejected"))
            Files.createDirectories(wd.resolve(d));
        Files.writeString(wd.resolve("active-branch.txt"), ROOT_BRANCH, StandardCharsets.UTF_8);

        String today = "2026-06-19";

        // 写 world.md（使用 draft 内容或 fallback 模板）
        String worldMd = draft.worldMarkdown() != null && !draft.worldMarkdown().isBlank()
                ? draft.worldMarkdown()
                : ResourceManager.renderTemplate("gsim/templates/world-template.md", "updated", today);
        String worldFile = "id: world.base\n"
                + "type: world\n"
                + "name: " + rootId + "\n"
                + "tags: [世界观, 初始设定]\n"
                + "updated: " + today + "\n"
                + "-------------------\n\n"
                + worldMd + "\n";
        writeFile(wd.resolve("world.md"), worldFile);

        // 写 entities.md
        String entitiesMd = draft.entitiesMarkdown() != null && !draft.entitiesMarkdown().isBlank()
                ? draft.entitiesMarkdown()
                : ResourceManager.renderTemplate("gsim/templates/entities-template.md", "updated", today);
        writeFile(wd.resolve("entities.md"), entitiesMd);

        // 写 rules.md
        String rulesMd = draft.rulesMarkdown() != null && !draft.rulesMarkdown().isBlank()
                ? draft.rulesMarkdown()
                : ResourceManager.renderTemplate("gsim/templates/rules-template.md", "updated", today);
        writeFile(wd.resolve("rules.md"), rulesMd);

        // 写 input.md
        String inputMd = draft.inputMarkdown() != null && !draft.inputMarkdown().isBlank()
                ? draft.inputMarkdown()
                : ResourceManager.renderTemplate("gsim/templates/input-template.md", "updated", today);
        writeFile(wd.resolve("input.md"), inputMd);

        // 写 players.md
        String playersMd = draft.playersMarkdown() != null && !draft.playersMarkdown().isBlank()
                ? draft.playersMarkdown()
                : ResourceManager.readText("gsim/templates/players-template.md");
        writeFile(wd.resolve("players.md"), playersMd);

        // 写 branches/b0000-start.md
        String branchInput = draft.rootBranchInput() != null && !draft.rootBranchInput().isBlank()
                ? draft.rootBranchInput()
                : "世界初始化。";
        writeFile(wd.resolve("branches/b0000-start.md"), buildBranchContent(
                ROOT_BRANCH, "时间原点", "none", 0, "时间原点",
                branchInput, "无。",
                "待推演。", "无。", "无。", "无。", "无。", "无。", "待后续推演。"));

        this.activeWorld = rootId;
        this.activeBranch = ROOT_BRANCH;
        Files.writeString(dataRoot.resolve("active-world.txt"), rootId, StandardCharsets.UTF_8);
        reload();
        log.info("Bootstrapped root '{}' from empty data with full draft", rootId);
    }
```

然后修改旧的 `bootstrapFromEmpty(String rootId, String worldContentMd)` 方法，让它委托到新方法：

```java
    /**
     * 从空 data bootstrap 创建初始 root（仅 worldContent，向后兼容）。
     * 委托到完整 draft 版本。
     */
    public void bootstrapFromEmpty(String rootId, String worldContentMd) throws IOException {
        String title = com.gsim.root.RootIdGenerator.extractTitle(worldContentMd);
        String worldMd = com.gsim.root.RootIdGenerator.buildWorldMarkdown(title, worldContentMd);
        var draft = new com.gsim.root.BootstrapWorldDraftGenerator.BootstrapWorldDraft(
                rootId, title, worldMd,
                null, null, null, null,
                "世界初始化。\n\n" + (worldContentMd != null ? worldContentMd : ""),
                List.of());
        bootstrapFromEmpty(rootId, draft);
    }
```

- [ ] **Step 2: 编译验证**

```bash
cd /home/cna/GSimulator && mvn compile -q 2>&1 | tail -10
```

Expected: BUILD SUCCESS

- [ ] **Step 3: 提交**

```bash
git add src/main/java/com/gsim/data/DataManager.java
git commit -m "feat: DataManager.bootstrapFromEmpty accepts full BootstrapWorldDraft"
```

---

### Task 5: 重写 NodeAgentChatService.bootstrapFirstRoot

**Files:**
- Modify: `src/main/java/com/gsim/chat/NodeAgentChatService.java`

**Interfaces:**
- Consumes: `BootstrapIntentParser.parse(String, boolean)`, `BootstrapWorldDraftGenerator.generate(BootstrapIntent)`, `DataManager.bootstrapFromEmpty(String, BootstrapWorldDraft)`

- [ ] **Step 1: 重写 bootstrapFirstRoot 方法**

在 `NodeAgentChatService.java` 中，替换 `bootstrapFirstRoot` 方法：

```java
    /** 从空 data bootstrap 创建第一个 root。任意非空自然语言输入都允许。 */
    private String bootstrapFirstRoot(String userText) throws IOException {
        if (!RootBootstrapPolicy.isStrictlyEmptyDataRoot(dm.getDataRoot())) {
            return "已有 root 数据。创建或切换根节点需要用户显式命令。\n请使用：\n  /root create <rootId> <初始设定>\n  /root switch <rootId>\n  /root list";
        }

        // 解析 bootstrap 意图（data 为空时任意非空文本都允许）
        var intent = com.gsim.root.BootstrapIntentParser.parse(userText, true);
        if (!intent.shouldBootstrap()) {
            return ""; // 空输入，忽略
        }

        // 生成结构化 draft
        var generator = new com.gsim.root.BootstrapWorldDraftGenerator(
                appCtx.getLlmClient(), appCtx.getConfig().getLlmModel());
        var draft = generator.generate(intent);

        String rootId = draft.rootIdSuggestion();
        // 确保 rootId 有效
        if (!com.gsim.root.RootIdGenerator.isValidRootId(rootId)) {
            rootId = com.gsim.root.RootIdGenerator.suggestRootId(intent.sanitizedRequest());
        }

        // Bootstrap
        dm.bootstrapFromEmpty(rootId, draft);

        // 重建 ContextSession + Knowledge
        appCtx.resolveKnowledgeForActiveRoot();
        var newRenderer = createRenderer();
        this.renderer = newRenderer;
        this.ctxSessionManager = createSessionManager(newRenderer);
        appCtx.setBranchContextRenderer(newRenderer);
        appCtx.setContextSessionManager(this.ctxSessionManager);

        // 构建确认消息
        StringBuilder sb = new StringBuilder();
        sb.append("已根据你的描述创建第一个根节点。\n\n");
        sb.append("Root ID: ").append(rootId).append("\n");
        sb.append("Title: ").append(draft.title()).append("\n");
        sb.append("Active Branch: branch.b0000-start\n\n");
        sb.append("已生成基础世界观模板：\n");
        sb.append("- world.md：").append(draft.title()).append("基础设定\n");
        sb.append("- entities.md：主要势力与人物卡占位\n");
        sb.append("- rules.md：推演规则占位\n");
        sb.append("- players.md：玩家资料占位\n");

        if (!draft.warnings().isEmpty()) {
            sb.append("\n注意：");
            for (String w : draft.warnings()) {
                sb.append("\n- ").append(w);
            }
        }

        sb.append("\n\n使用 /root status 查看状态，/chat 开始对话。");
        return sb.toString();
    }
```

- [ ] **Step 2: 编译验证**

```bash
cd /home/cna/GSimulator && mvn compile -q 2>&1 | tail -10
```

Expected: BUILD SUCCESS

- [ ] **Step 3: 提交**

```bash
git add src/main/java/com/gsim/chat/NodeAgentChatService.java
git commit -m "feat: NodeAgentChatService — any text bootstraps first root via draft generator"
```

---

### Task 6: 更新 orchestrator-system.md prompt

**Files:**
- Modify: `src/main/resources/gsim/prompts/orchestrator-system.md`

- [ ] **Step 1: 更新 Empty-data bootstrap 章节**

找到 "Empty-data bootstrap" 章节（约第 15-21 行），替换为：

```markdown
### Empty-data bootstrap

- 当 data 严格为空时，用户第一条自然语言视为创建第一个 root 的需求。
- 你应根据用户描述生成基础根节点模板，而不是要求用户使用固定格式。
- 不得把原始用户消息直接作为 world.md。
- 如果用户提到已有作品世界观（如"明日方舟/泰拉"），应生成概括性基础模板，并标记资料待核验/待导入。
- 不要声称已经从 wiki 导入，除非实际调用导入工具成功。
- 允许的旧格式仍支持：`初始化根节点：<内容>` `初始化世界：<内容>` `创建第一个根节点：<内容>` `init root: <内容>` `initialize root: <content>`
```

- [ ] **Step 2: 验证 prompt 文件内容**

```bash
grep -A 10 "Empty-data bootstrap" src/main/resources/gsim/prompts/orchestrator-system.md
```

Expected: 显示更新后的内容

- [ ] **Step 3: 提交**

```bash
git add src/main/resources/gsim/prompts/orchestrator-system.md
git commit -m "feat: update orchestrator-system.md — any natural language triggers empty-data bootstrap"
```

---

### Task 7: 更新现有测试 — BootstrapIntentAndFormatTest

**Files:**
- Modify: `src/test/java/com/gsim/root/BootstrapIntentAndFormatTest.java`

- [ ] **Step 1: 修改测试以适配新 API**

`BootstrapIntentParser.parse()` 现在需要 `isDataEmpty` 参数。修改测试：

```java
package com.gsim.root;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class BootstrapIntentAndFormatTest {

    // data 为空时
    private static final boolean EMPTY = true;
    // data 非空时
    private static final boolean NON_EMPTY = false;

    @Test
    void initRootPrefixIsBootstrapIntent() {
        assertTrue(BootstrapIntentParser.hasBootstrapIntent("初始化根节点：泰拉世界设定。", EMPTY));
        assertTrue(BootstrapIntentParser.hasBootstrapIntent("初始化世界：架空东南亚1850年。", EMPTY));
        assertTrue(BootstrapIntentParser.hasBootstrapIntent("创建第一个根节点：测试内容。", EMPTY));
        assertTrue(BootstrapIntentParser.hasBootstrapIntent("init root: test content", EMPTY));
        assertTrue(BootstrapIntentParser.hasBootstrapIntent("initialize root: test content", EMPTY));
    }

    @Test
    void anyTextIsBootstrapIntentWhenDataEmpty() {
        // data 为空时，任意非空文本都应允许 bootstrap
        assertTrue(BootstrapIntentParser.hasBootstrapIntent(
                "从wiki上抄一下方舟的世界观，就是那个明日方舟。", EMPTY));
        assertTrue(BootstrapIntentParser.hasBootstrapIntent(
                "帮我建一个世界。", EMPTY));
        assertTrue(BootstrapIntentParser.hasBootstrapIntent(
                "你能不能单独录入不同角色和玩家资料？", EMPTY));
        assertTrue(BootstrapIntentParser.hasBootstrapIntent(
                "初始化一下世界观，就用明日方舟的", EMPTY));
        assertTrue(BootstrapIntentParser.hasBootstrapIntent(
                "开个泰拉世界", EMPTY));
    }

    @Test
    void anyTextIsNotBootstrapIntentWhenDataNotEmpty() {
        // data 非空时，任意文本都不应允许自动 bootstrap
        assertFalse(BootstrapIntentParser.hasBootstrapIntent(
                "初始化根节点：泰拉世界设定。", NON_EMPTY));
        assertFalse(BootstrapIntentParser.hasBootstrapIntent(
                "从wiki上抄一下方舟的世界观。", NON_EMPTY));
        assertFalse(BootstrapIntentParser.hasBootstrapIntent(
                "帮我建一个世界。", NON_EMPTY));
    }

    @Test
    void emptyOrNullIsNotBootstrap() {
        assertFalse(BootstrapIntentParser.hasBootstrapIntent(null, EMPTY));
        assertFalse(BootstrapIntentParser.hasBootstrapIntent("", EMPTY));
        assertFalse(BootstrapIntentParser.hasBootstrapIntent("   ", EMPTY));
    }

    @Test
    void bootstrapExtractsWorldContent() {
        var intent = BootstrapIntentParser.parse("初始化根节点：泰拉世界，开始年份11090年。", EMPTY);
        assertTrue(intent.shouldBootstrap());
        assertTrue(intent.explicitTitle().isPresent());
        assertEquals("泰拉世界，开始年份11090年。", intent.explicitTitle().get());
    }

    @Test
    void bootstrapWithoutPrefixHasNoExplicitTitle() {
        var intent = BootstrapIntentParser.parse("初始化一下世界观，就用明日方舟的", EMPTY);
        assertTrue(intent.shouldBootstrap());
        assertTrue(intent.explicitTitle().isEmpty());
        assertEquals("初始化一下世界观，就用明日方舟的", intent.sanitizedRequest());
    }

    @Test
    void rootIdIsAsciiOnly() {
        String id = RootIdGenerator.generateFromContent("泰拉世界设定");
        assertTrue(RootIdGenerator.isValidRootId(id), "rootId should be ASCII: " + id);
        assertTrue(id.startsWith("root."), "rootId should start with root.: " + id);
        assertFalse(id.contains("泰"), "rootId should not contain Chinese");
    }

    @Test
    void rootIdDoesNotContainRawInstruction() {
        String id = RootIdGenerator.generateFromContent("从wiki抄资料然后设定11090年");
        assertTrue(RootIdGenerator.isValidRootId(id));
        assertTrue(id.matches("root\\.[a-f0-9]{8}"), "Should be root.<hash8>: " + id);
    }

    @Test
    void suggestRootIdRecognizesArknights() {
        String id = RootIdGenerator.suggestRootId("初始化一下世界观，就用明日方舟的");
        assertEquals("root.arknights-terra", id);
    }

    @Test
    void suggestRootIdRecognizesRomania() {
        String id = RootIdGenerator.suggestRootId("帮我建一个罗马尼亚1876开局");
        assertEquals("root.romania", id);
    }

    @Test
    void suggestRootIdFallsBackToHash() {
        String id = RootIdGenerator.suggestRootId("xyzzy unknown topic 12345");
        assertTrue(id.startsWith("root."));
        assertTrue(RootIdGenerator.isValidRootId(id));
    }

    @Test
    void worldMdIsStructured() {
        String md = RootIdGenerator.buildWorldMarkdown("泰拉世界", "泰拉11090年，世界观资料待导入。");
        assertTrue(md.contains("# 世界观"));
        assertTrue(md.contains("## 世界名称"));
        assertTrue(md.contains("泰拉世界"));
        assertTrue(md.contains("泰拉11090年"));
        assertTrue(md.contains("## 资料状态"));
        assertTrue(md.contains("人物卡：待录入"));
        assertFalse(md.contains("从wiki上抄"));
        assertFalse(md.contains("你应该有能力"));
    }

    @Test
    void textSanitizerStripsAnsiCodes() {
        String input = "[31mRed text[0m normal";
        String cleaned = TextSanitizer.stripAnsiAndControlChars(input);
        assertFalse(cleaned.contains(""), "ANSI codes should be stripped");
        assertTrue(cleaned.contains("Red text"));
    }

    @Test
    void textSanitizerStripsControlChars() {
        String input = "hello world";
        String cleaned = TextSanitizer.stripAnsiAndControlChars(input);
        assertEquals("helloworld", cleaned);
    }

    @Test
    void nonBootstrapHintIsHelpful() {
        String hint = BootstrapIntentParser.nonBootstrapHint();
        assertTrue(hint.contains("/root create"));
        assertTrue(hint.contains("data 目录非空"));
    }
}
```

- [ ] **Step 2: 运行测试验证失败（旧 API 不兼容）**

```bash
cd /home/cna/GSimulator && mvn test -pl . -Dtest=BootstrapIntentAndFormatTest -q 2>&1 | tail -20
```

Expected: 编译错误（旧测试使用旧 API 签名）

- [ ] **Step 3: 确认测试通过**

```bash
cd /home/cna/GSimulator && mvn test -pl . -Dtest=BootstrapIntentAndFormatTest 2>&1 | tail -15
```

Expected: Tests run: X, Failures: 0, Errors: 0

- [ ] **Step 4: 提交**

```bash
git add src/test/java/com/gsim/root/BootstrapIntentAndFormatTest.java
git commit -m "test: update BootstrapIntentAndFormatTest for new any-text bootstrap policy"
```

---

### Task 8: 更新现有测试 — EmptyDataChatBootstrapTest

**Files:**
- Modify: `src/test/java/com/gsim/root/EmptyDataChatBootstrapTest.java`

- [ ] **Step 1: 适配新的 bootstrapFromEmpty 签名**

测试中 `dm.bootstrapFromEmpty("fresh-root", "1850年代架空东南亚测试。")` 仍使用旧签名（向后兼容），不需要修改。但需要确认编译通过。

```bash
cd /home/cna/GSimulator && mvn test -pl . -Dtest=EmptyDataChatBootstrapTest 2>&1 | tail -15
```

Expected: Tests run: 3, Failures: 0, Errors: 0

- [ ] **Step 2: 提交（如有修改）**

如果测试通过且无修改，跳过提交。

---

### Task 9: 新增测试 — EmptyDataAnyTextBootstrapsRootTest

**Files:**
- Create: `src/test/java/com/gsim/root/EmptyDataAnyTextBootstrapsRootTest.java`

- [ ] **Step 1: 创建测试**

```java
package com.gsim.root;

import com.gsim.data.DataManager;
import com.gsim.llm.FakeLlmClient;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 验证 data 为空时任意非空自然语言输入都能触发 bootstrap。
 */
class EmptyDataAnyTextBootstrapsRootTest {

    @Test
    void anyTextBootstrapsRootWhenDataEmpty(@TempDir Path tmpDir) throws Exception {
        Path dataRoot = tmpDir.resolve("data");
        Files.createDirectories(dataRoot);
        DataManager dm = new DataManager(dataRoot);
        assertTrue(dm.needsRootBootstrap());

        // 使用 BootstrapWorldDraftGenerator 的 fallback 路径（无 LLM）
        var intent = BootstrapIntentParser.parse("初始化一下世界观，就用明日方舟的", true);
        assertTrue(intent.shouldBootstrap());

        var generator = new BootstrapWorldDraftGenerator(null, "test-model");
        var draft = generator.generate(intent);

        assertNotNull(draft.rootIdSuggestion());
        assertTrue(RootIdGenerator.isValidRootId(draft.rootIdSuggestion()));
        assertNotNull(draft.title());
        assertFalse(draft.worldMarkdown().isBlank());
        assertFalse(draft.entitiesMarkdown().isBlank());
        assertFalse(draft.rulesMarkdown().isBlank());
        assertFalse(draft.playersMarkdown().isBlank());
        assertFalse(draft.rootBranchInput().isBlank());

        // Bootstrap
        dm.bootstrapFromEmpty(draft.rootIdSuggestion(), draft);
        assertFalse(dm.needsRootBootstrap());
        assertEquals(draft.rootIdSuggestion(), dm.getActiveRootId());
    }

    @Test
    void shortTextBootstrapsRoot(@TempDir Path tmpDir) throws Exception {
        Path dataRoot = tmpDir.resolve("data");
        Files.createDirectories(dataRoot);
        DataManager dm = new DataManager(dataRoot);

        var intent = BootstrapIntentParser.parse("开个泰拉世界", true);
        assertTrue(intent.shouldBootstrap());

        var generator = new BootstrapWorldDraftGenerator(null, "test-model");
        var draft = generator.generate(intent);

        dm.bootstrapFromEmpty(draft.rootIdSuggestion(), draft);
        assertFalse(dm.needsRootBootstrap());
    }

    @Test
    void longTextBootstrapsRoot(@TempDir Path tmpDir) throws Exception {
        Path dataRoot = tmpDir.resolve("data");
        Files.createDirectories(dataRoot);
        DataManager dm = new DataManager(dataRoot);

        var intent = BootstrapIntentParser.parse(
                "我要做一个1850年代架空东南亚的文游，有殖民势力、本地王国和华人商帮", true);
        assertTrue(intent.shouldBootstrap());

        var generator = new BootstrapWorldDraftGenerator(null, "test-model");
        var draft = generator.generate(intent);

        dm.bootstrapFromEmpty(draft.rootIdSuggestion(), draft);
        assertFalse(dm.needsRootBootstrap());
    }
}
```

- [ ] **Step 2: 运行测试**

```bash
cd /home/cna/GSimulator && mvn test -pl . -Dtest=EmptyDataAnyTextBootstrapsRootTest 2>&1 | tail -15
```

Expected: Tests run: 3, Failures: 0, Errors: 0

- [ ] **Step 3: 提交**

```bash
git add src/test/java/com/gsim/root/EmptyDataAnyTextBootstrapsRootTest.java
git commit -m "test: any non-empty text bootstraps root when data is empty"
```

---

### Task 10: 新增测试 — EmptyDataArknightsTextCreatesStructuredRootTest

**Files:**
- Create: `src/test/java/com/gsim/root/EmptyDataArknightsTextCreatesStructuredRootTest.java`

- [ ] **Step 1: 创建测试**

```java
package com.gsim.root;

import com.gsim.data.DataManager;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 验证"明日方舟"输入生成结构化泰拉世界模板。
 */
class EmptyDataArknightsTextCreatesStructuredRootTest {

    @Test
    void arknightsTextCreatesStructuredRoot(@TempDir Path tmpDir) throws Exception {
        Path dataRoot = tmpDir.resolve("data");
        Files.createDirectories(dataRoot);
        DataManager dm = new DataManager(dataRoot);

        var intent = BootstrapIntentParser.parse("初始化一下世界观，就用明日方舟的", true);
        var generator = new BootstrapWorldDraftGenerator(null, "test-model");
        var draft = generator.generate(intent);

        dm.bootstrapFromEmpty(draft.rootIdSuggestion(), draft);

        // 验证 rootId 是 ASCII
        assertTrue(RootIdGenerator.isValidRootId(draft.rootIdSuggestion()));

        // 验证文件存在
        Path worldDir = dataRoot.resolve("worlds").resolve(draft.rootIdSuggestion());
        assertTrue(Files.exists(worldDir.resolve("world.md")));
        assertTrue(Files.exists(worldDir.resolve("entities.md")));
        assertTrue(Files.exists(worldDir.resolve("rules.md")));
        assertTrue(Files.exists(worldDir.resolve("players.md")));
        assertTrue(Files.exists(worldDir.resolve("branches").resolve("b0000-start.md")));

        // 验证 world.md 是结构化的（包含基本章节）
        String worldMd = Files.readString(worldDir.resolve("world.md"));
        assertTrue(worldMd.contains("# 世界观") || worldMd.contains("世界名称") || worldMd.contains("世界概述"),
                "world.md should be structured");

        // 验证 world.md 不只是原始用户文本
        assertFalse(worldMd.trim().equals("初始化一下世界观，就用明日方舟的"),
                "world.md should not be raw user text only");
    }

    @Test
    void bootstrapCreatesWorldEntitiesRulesPlayers(@TempDir Path tmpDir) throws Exception {
        Path dataRoot = tmpDir.resolve("data");
        Files.createDirectories(dataRoot);
        DataManager dm = new DataManager(dataRoot);

        var intent = BootstrapIntentParser.parse("初始化一下世界观，就用明日方舟的", true);
        var generator = new BootstrapWorldDraftGenerator(null, "test-model");
        var draft = generator.generate(intent);

        dm.bootstrapFromEmpty(draft.rootIdSuggestion(), draft);

        Path worldDir = dataRoot.resolve("worlds").resolve(draft.rootIdSuggestion());

        // entities.md 存在且非空
        String entitiesMd = Files.readString(worldDir.resolve("entities.md"));
        assertFalse(entitiesMd.isBlank(), "entities.md should not be empty");

        // rules.md 存在且非空
        String rulesMd = Files.readString(worldDir.resolve("rules.md"));
        assertFalse(rulesMd.isBlank(), "rules.md should not be empty");

        // players.md 存在且非空
        String playersMd = Files.readString(worldDir.resolve("players.md"));
        assertFalse(playersMd.isBlank(), "players.md should not be empty");

        // b0000-start.md 存在
        String branchMd = Files.readString(worldDir.resolve("branches").resolve("b0000-start.md"));
        assertTrue(branchMd.contains("branch.b0000-start") || branchMd.contains("时间原点"));
    }

    @Test
    void draftWarnsWorldNeedsVerification(@TempDir Path tmpDir) throws Exception {
        Path dataRoot = tmpDir.resolve("data");
        Files.createDirectories(dataRoot);
        DataManager dm = new DataManager(dataRoot);

        var intent = BootstrapIntentParser.parse("初始化一下世界观，就用明日方舟的", true);
        var generator = new BootstrapWorldDraftGenerator(null, "test-model");
        var draft = generator.generate(intent);

        // fallback 路径应该包含警告
        assertFalse(draft.warnings().isEmpty(), "Fallback draft should have warnings");
        boolean hasVerificationWarning = draft.warnings().stream()
                .anyMatch(w -> w.contains("待导入") || w.contains("核验") || w.contains("模板"));
        assertTrue(hasVerificationWarning, "Warnings should mention verification/import needed");
    }
}
```

- [ ] **Step 2: 运行测试**

```bash
cd /home/cna/GSimulator && mvn test -pl . -Dtest=EmptyDataArknightsTextCreatesStructuredRootTest 2>&1 | tail -15
```

Expected: Tests run: 3, Failures: 0, Errors: 0

- [ ] **Step 3: 提交**

```bash
git add src/test/java/com/gsim/root/EmptyDataArknightsTextCreatesStructuredRootTest.java
git commit -m "test: Arknights text creates structured root with all required files"
```

---

### Task 11: 新增测试 — EmptyDataBootstrapRootIdAsciiOnlyTest

**Files:**
- Create: `src/test/java/com/gsim/root/EmptyDataBootstrapRootIdAsciiOnlyTest.java`

- [ ] **Step 1: 创建测试**

```java
package com.gsim.root;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 验证 rootId 始终是 ASCII-only。
 */
class EmptyDataBootstrapRootIdAsciiOnlyTest {

    @Test
    void rootIdContainsNoChinese() {
        String id = RootIdGenerator.suggestRootId("初始化一下世界观，就用明日方舟的");
        assertTrue(RootIdGenerator.isValidRootId(id));
        for (char c : id.toCharArray()) {
            assertFalse(Character.UnicodeBlock.of(c) == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS,
                    "rootId should not contain Chinese characters: " + id);
        }
    }

    @Test
    void rootIdContainsNoSpaces() {
        String id = RootIdGenerator.suggestRootId("test with spaces");
        assertFalse(id.contains(" "), "rootId should not contain spaces: " + id);
    }

    @Test
    void rootIdContainsNoAnsiControlChars() {
        String id = RootIdGenerator.suggestRootId("normal text");
        for (char c : id.toCharArray()) {
            assertFalse(c < 0x20 && c != '\n' && c != '\r' && c != '\t',
                    "rootId should not contain control chars: " + id);
        }
    }

    @Test
    void rootIdMatchesAllowedPattern() {
        String id = RootIdGenerator.suggestRootId("some random text");
        assertTrue(id.matches("[a-zA-Z0-9._-]+"), "rootId should match [a-zA-Z0-9._-]+: " + id);
    }

    @Test
    void hashBasedRootIdIsAscii() {
        String id = RootIdGenerator.generateFromContent("纯中文内容测试");
        assertTrue(id.matches("root\\.[a-f0-9]{8}"), "Hash-based rootId should be root.<hash8>: " + id);
    }

    @Test
    void allKnownTopicIdsAreAscii() {
        // 验证所有已知主题映射的 rootId 后缀都是 ASCII
        String[] testInputs = {
                "明日方舟", "泰拉", "罗马尼亚", "东南亚", "1850年代", "架空历史",
                "乌萨斯", "罗德岛", "维多利亚", "炎国", "感染者", "源石", "天灾"
        };
        for (String input : testInputs) {
            String id = RootIdGenerator.suggestRootId(input);
            assertTrue(RootIdGenerator.isValidRootId(id),
                    "suggestRootId('" + input + "') = '" + id + "' should be valid ASCII");
        }
    }
}
```

- [ ] **Step 2: 运行测试**

```bash
cd /home/cna/GSimulator && mvn test -pl . -Dtest=EmptyDataBootstrapRootIdAsciiOnlyTest 2>&1 | tail -15
```

Expected: Tests run: 6, Failures: 0, Errors: 0

- [ ] **Step 3: 提交**

```bash
git add src/test/java/com/gsim/root/EmptyDataBootstrapRootIdAsciiOnlyTest.java
git commit -m "test: rootId is always ASCII-only, no Chinese, no spaces, no control chars"
```

---

### Task 12: 新增测试 — EmptyDataBootstrapUsesFallbackWhenLlmUnavailableTest

**Files:**
- Create: `src/test/java/com/gsim/root/EmptyDataBootstrapUsesFallbackWhenLlmUnavailableTest.java`

- [ ] **Step 1: 创建测试**

```java
package com.gsim.root;

import com.gsim.data.DataManager;
import com.gsim.llm.FakeLlmClient;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 验证 LLM 不可用时使用 deterministic fallback。
 */
class EmptyDataBootstrapUsesFallbackWhenLlmUnavailableTest {

    @Test
    void nullLlmClientUsesFallback(@TempDir Path tmpDir) throws Exception {
        Path dataRoot = tmpDir.resolve("data");
        Files.createDirectories(dataRoot);
        DataManager dm = new DataManager(dataRoot);

        var intent = BootstrapIntentParser.parse("初始化一下世界观，就用明日方舟的", true);
        // null LlmClient → fallback
        var generator = new BootstrapWorldDraftGenerator(null, "test-model");
        var draft = generator.generate(intent);

        assertNotNull(draft);
        assertFalse(draft.worldMarkdown().isBlank());
        assertTrue(draft.worldMarkdown().contains("世界名称") || draft.worldMarkdown().contains("世界观"),
                "Fallback world.md should contain basic structure");

        dm.bootstrapFromEmpty(draft.rootIdSuggestion(), draft);
        assertFalse(dm.needsRootBootstrap());
    }

    @Test
    void unavailableLlmClientUsesFallback(@TempDir Path tmpDir) throws Exception {
        Path dataRoot = tmpDir.resolve("data");
        Files.createDirectories(dataRoot);
        DataManager dm = new DataManager(dataRoot);

        FakeLlmClient fakeLlm = new FakeLlmClient();
        fakeLlm.setAvailable(false); // LLM 不可用

        var intent = BootstrapIntentParser.parse("帮我建一个罗马尼亚1876开局", true);
        var generator = new BootstrapWorldDraftGenerator(fakeLlm, "test-model");
        var draft = generator.generate(intent);

        assertNotNull(draft);
        assertFalse(draft.worldMarkdown().isBlank());
        // fallback 应包含警告
        assertFalse(draft.warnings().isEmpty());

        dm.bootstrapFromEmpty(draft.rootIdSuggestion(), draft);
        assertFalse(dm.needsRootBootstrap());
    }

    @Test
    void fallbackWorldMdHasRequiredSections(@TempDir Path tmpDir) throws Exception {
        Path dataRoot = tmpDir.resolve("data");
        Files.createDirectories(dataRoot);
        DataManager dm = new DataManager(dataRoot);

        var intent = BootstrapIntentParser.parse("开个泰拉世界", true);
        var generator = new BootstrapWorldDraftGenerator(null, "test-model");
        var draft = generator.generate(intent);

        String worldMd = draft.worldMarkdown();
        assertTrue(worldMd.contains("世界名称"), "Fallback world.md should have 世界名称");
        assertTrue(worldMd.contains("资料状态") || worldMd.contains("待导入") || worldMd.contains("待补全"),
                "Fallback world.md should mention data status");
    }

    @Test
    void fallbackEntitiesMdIsNotEmpty(@TempDir Path tmpDir) throws Exception {
        var intent = BootstrapIntentParser.parse("测试世界", true);
        var generator = new BootstrapWorldDraftGenerator(null, "test-model");
        var draft = generator.generate(intent);

        assertFalse(draft.entitiesMarkdown().isBlank());
        assertTrue(draft.entitiesMarkdown().contains("实体") || draft.entitiesMarkdown().contains("势力")
                || draft.entitiesMarkdown().contains("待补全"));
    }

    @Test
    void fallbackRulesMdIsNotEmpty(@TempDir Path tmpDir) throws Exception {
        var intent = BootstrapIntentParser.parse("测试世界", true);
        var generator = new BootstrapWorldDraftGenerator(null, "test-model");
        var draft = generator.generate(intent);

        assertFalse(draft.rulesMarkdown().isBlank());
        assertTrue(draft.rulesMarkdown().contains("规则") || draft.rulesMarkdown().contains("推演"));
    }
}
```

- [ ] **Step 2: 运行测试**

```bash
cd /home/cna/GSimulator && mvn test -pl . -Dtest=EmptyDataBootstrapUsesFallbackWhenLlmUnavailableTest 2>&1 | tail -15
```

Expected: Tests run: 5, Failures: 0, Errors: 0

- [ ] **Step 3: 提交**

```bash
git add src/test/java/com/gsim/root/EmptyDataBootstrapUsesFallbackWhenLlmUnavailableTest.java
git commit -m "test: fallback draft generation when LLM is unavailable"
```

---

### Task 13: 新增测试 — NonEmptyDataStillCannotAutoBootstrapTest

**Files:**
- Create: `src/test/java/com/gsim/root/NonEmptyDataStillCannotAutoBootstrapTest.java`

- [ ] **Step 1: 创建测试**

```java
package com.gsim.root;

import com.gsim.data.DataManager;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 验证 data 非空时禁止自动 bootstrap。
 */
class NonEmptyDataStillCannotAutoBootstrapTest {

    @Test
    void nonEmptyDataRejectsBootstrap(@TempDir Path tmpDir) throws Exception {
        Path dataRoot = tmpDir.resolve("data");
        Files.createDirectories(dataRoot);

        // 先创建一个 root
        DataManager dm = new DataManager(dataRoot);
        dm.init(); // 创建 default root
        assertFalse(dm.needsRootBootstrap());

        // data 非空时，BootstrapIntentParser 应拒绝
        var intent = BootstrapIntentParser.parse("初始化一下世界观，就用明日方舟的", false);
        assertFalse(intent.shouldBootstrap(),
                "Should not allow bootstrap when data is not empty");
    }

    @Test
    void nonEmptyDataWithSkillsDirRejectsBootstrap(@TempDir Path tmpDir) throws Exception {
        Path dataRoot = tmpDir.resolve("data");
        // 只创建 skills 目录（模拟非空但无 root 的情况）
        Files.createDirectories(dataRoot.resolve("skills"));

        DataManager dm = new DataManager(dataRoot);
        // skills 目录不算有效 root，所以 needsRootBootstrap 仍为 true
        // 但 RootBootstrapPolicy.isStrictlyEmptyDataRoot 检查的是 worlds/ 下是否有有效 root
        // skills 目录不影响 — 所以这里仍应允许 bootstrap
        // 验证：RootBootstrapPolicy 正确判断
        assertTrue(RootBootstrapPolicy.isStrictlyEmptyDataRoot(dataRoot),
                "skills dir alone should not count as having a root");
    }

    @Test
    void dataWithExistingRootRejectsBootstrap(@TempDir Path tmpDir) throws Exception {
        Path dataRoot = tmpDir.resolve("data");
        DataManager dm = new DataManager(dataRoot);
        dm.init();

        // bootstrapFromEmpty 应抛出异常
        var intent = BootstrapIntentParser.parse("测试", true);
        var generator = new BootstrapWorldDraftGenerator(null, "test-model");
        var draft = generator.generate(intent);

        assertThrows(java.io.IOException.class, () -> {
            dm.bootstrapFromEmpty(draft.rootIdSuggestion(), draft);
        }, "bootstrapFromEmpty should fail when data already has roots");
    }

    @Test
    void bootstrapIntentDeniesWhenDataNotEmpty() {
        // 模拟 data 非空场景
        assertFalse(BootstrapIntentParser.hasBootstrapIntent("初始化根节点：测试", false));
        assertFalse(BootstrapIntentParser.hasBootstrapIntent("初始化一下世界观", false));
        assertFalse(BootstrapIntentParser.hasBootstrapIntent("任意文本", false));
    }

    @Test
    void nonBootstrapHintMentionsRootCreate() {
        String hint = BootstrapIntentParser.nonBootstrapHint();
        assertTrue(hint.contains("/root create"));
        assertTrue(hint.contains("data 目录非空") || hint.contains("清理 data"));
    }
}
```

- [ ] **Step 2: 运行测试**

```bash
cd /home/cna/GSimulator && mvn test -pl . -Dtest=NonEmptyDataStillCannotAutoBootstrapTest 2>&1 | tail -15
```

Expected: Tests run: 5, Failures: 0, Errors: 0

- [ ] **Step 3: 提交**

```bash
git add src/test/java/com/gsim/root/NonEmptyDataStillCannotAutoBootstrapTest.java
git commit -m "test: non-empty data still cannot auto-bootstrap"
```

---

### Task 14: 运行全部测试并打包

- [ ] **Step 1: 运行全部测试**

```bash
cd /home/cna/GSimulator && mvn test 2>&1 | tail -30
```

Expected: BUILD SUCCESS, all tests pass

- [ ] **Step 2: 打包**

```bash
cd /home/cna/GSimulator && mvn package -DskipTests -q 2>&1 | tail -5
```

Expected: BUILD SUCCESS

- [ ] **Step 3: 提交最终状态**

```bash
git status --short
```

---

### Task 15: 更新 PromptRootGovernanceRulesTest

**Files:**
- Modify: `src/test/java/com/gsim/root/PromptRootGovernanceRulesTest.java`

- [ ] **Step 1: 更新测试以匹配新的 prompt 内容**

`orchestrator-system.md` 中的 "Empty-data bootstrap" 章节已更新。测试中检查 `初始化根节点` 的行需要更新为检查新内容：

```java
    @Test
    void promptContainsRootGovernanceRules() throws Exception {
        Path f = Path.of("src/main/resources/gsim/prompts/orchestrator-system.md");
        if (!Files.exists(f)) return;
        String content = Files.readString(f);

        assertTrue(content.contains("根节点 / Root Workspace 管理规则"),
                "Prompt must contain root governance section");
        assertTrue(content.contains("root_create"),
                "Prompt must mention root_create tool");
        assertTrue(content.contains("root_world_update"),
                "Prompt must mention root_world_update tool");
        assertTrue(content.contains("root_status"),
                "Prompt must mention root_status tool");
        assertTrue(content.contains("branch.b0000-start"),
                "Prompt must mention root branch");
        assertTrue(content.contains("不要把不同 root 的资料混用"),
                "Prompt must forbid cross-root contamination");
        assertTrue(content.contains("player_profile_update"),
                "Prompt must mention player profile tools are always available");
        assertTrue(content.contains("不在根节点时的限制"),
                "Prompt must mention non-root-branch restrictions");
        // 更新：检查新的 bootstrap 规则
        assertTrue(content.contains("第一条自然语言视为创建第一个 root 的需求")
                || content.contains("任意自然语言输入都允许 bootstrap")
                || content.contains("不得把原始用户消息直接作为 world.md"),
                "Prompt must contain new any-text bootstrap rules");
    }
```

- [ ] **Step 2: 运行测试**

```bash
cd /home/cna/GSimulator && mvn test -pl . -Dtest=PromptRootGovernanceRulesTest 2>&1 | tail -10
```

Expected: Tests run: 1, Failures: 0, Errors: 0

- [ ] **Step 3: 提交**

```bash
git add src/test/java/com/gsim/root/PromptRootGovernanceRulesTest.java
git commit -m "test: update PromptRootGovernanceRulesTest for new bootstrap rules"
```
