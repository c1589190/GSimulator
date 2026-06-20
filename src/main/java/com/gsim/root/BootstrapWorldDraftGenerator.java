package com.gsim.root;

import com.gsim.llm.LlmManager;
import com.gsim.llm.LlmMessage;
import com.gsim.llm.LlmRequest;
import com.gsim.llm.LlmResult;
import com.gsim.resource.ResourceManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * 根据用户第一条消息生成结构化根节点初稿。
 *
 * <p>默认使用 deterministic fallback 快速创建基础根节点。
 * 仅在配置显式开启 bootstrap.root.llm.enabled=true 且 LLM 可用时，才调用 LLM 生成 draft。
 */
public class BootstrapWorldDraftGenerator {
    private static final Logger log = LoggerFactory.getLogger(BootstrapWorldDraftGenerator.class);

    private final LlmManager llmManager;
    private final String model;
    private final boolean llmEnabled;

    /**
     * 二参构造函数（源码兼容，默认走 deterministic fallback）。
     * 如需 LLM bootstrap，请使用三参构造并传入 llmEnabled=true。
     */
    public BootstrapWorldDraftGenerator(LlmManager llmManager, String model) {
        this(llmManager, model, false);
    }

    public BootstrapWorldDraftGenerator(LlmManager llmManager, String model, boolean llmEnabled) {
        this.llmManager = llmManager;
        this.model = model;
        this.llmEnabled = llmEnabled;
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
     * 默认走 deterministic fallback（快速创建基础根节点）。
     * 仅当配置显式开启 bootstrap.root.llm.enabled=true 时才调用 LLM。
     */
    public BootstrapWorldDraft generate(BootstrapIntentParser.BootstrapIntent intent) {
        if (llmEnabled && llmManager != null && llmManager.isAvailable()) {
            try {
                return generateWithLlm(intent);
            } catch (Exception e) {
                log.warn("LLM draft generation failed, falling back to deterministic: {}", e.getMessage());
            }
        }
        return generateFallback(intent);
    }

    /**
     * LLM 路径：仅在 bootstrap.root.llm.enabled=true 且 LLM 可用时调用。
     * 调用 LLM 生成结构化 draft，解析失败时回退到 fallback。
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
        LlmResult response = llmManager.chat(request);

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
     * 如果检测到明日方舟/泰拉关键词，使用 Arknights 专用 fallback。
     */
    BootstrapWorldDraft generateFallback(BootstrapIntentParser.BootstrapIntent intent) {
        String sanitized = intent.sanitizedRequest();
        String rootId = RootIdGenerator.suggestRootId(sanitized);
        String title = extractFallbackTitle(intent);

        boolean isArknights = isArknightsIntent(sanitized);

        String worldMd = isArknights ? buildArknightsWorldMd(title, sanitized) : buildFallbackWorldMd(intent);
        String entitiesMd = isArknights ? buildArknightsEntitiesMd() : buildFallbackEntitiesMd();
        String rulesMd = buildFallbackRulesMd();
        String inputMd = "# 当前输入\n\n暂无待结算内容。\n";
        String playersMd = buildFallbackPlayersMd();
        String branchInput = "世界初始化。基于用户描述：" + truncate(sanitized, 200);
        List<String> warnings = List.of(
                "这是本地 fallback 生成的基础设定，未从 wiki 导入；专有名词需后续核验。"
        );

        return new BootstrapWorldDraft(rootId, title, worldMd, entitiesMd,
                rulesMd, inputMd, playersMd, branchInput, warnings);
    }

    /** 检测是否为明日方舟/泰拉世界观意图。 */
    private static boolean isArknightsIntent(String text) {
        if (text == null) return false;
        String lower = text.toLowerCase();
        return lower.contains("明日方舟") || lower.contains("泰拉")
                || lower.contains("arknights") || lower.contains("罗德岛")
                || lower.contains("源石") || lower.contains("矿石病");
    }

    /** Arknights/Terra 专用 deterministic fallback: world.md。 */
    private String buildArknightsWorldMd(String title, String userSummary) {
        return "# 世界观\n\n"
                + "## 世界名称\n\n" + title + "\n\n"
                + "## 世界概述\n\n"
                + "泰拉（Terra）是一个与地球迥异的架空世界。\n"
                + "本 root 基于用户描述创建：" + truncate(userSummary, 200) + "\n\n"
                + "## 核心设定\n\n"
                + "### 源石\n\n"
                + "源石（Originium）是泰拉世界广泛存在的半有机半无机矿物，"
                + "既是驱动工业与科技的核心能源，也是矿石病（源石病）的根源。\n"
                + "源石可被加工为源石技艺增幅器、能源核心、武器等。\n\n"
                + "### 矿石病 / 源石病\n\n"
                + "长期接触源石或源石粉尘可能导致矿石病（Oripathy）。\n"
                + "感染者体表逐渐出现源石结晶，同时可能获得源石技艺增幅，"
                + "但最终走向死亡。感染者被广泛歧视。\n\n"
                + "### 天灾\n\n"
                + "天灾（Catastrophe）是泰拉大陆规律性发生的自然灾害，"
                + "包括但不限于暴风雪、陨石、地震、源石尘暴等。\n"
                + "天灾过后往往会留下大量源石矿脉，因此天灾推动移动城市的迁徙周期。\n\n"
                + "### 移动城市 / 移动城邦\n\n"
                + "泰拉大陆的大多数城市建造在巨型移动平台上，以规避天灾。\n"
                + "移动城市（Mobile City）的迁徙路线由天灾信使和城市引擎共同规划。\n\n"
                + "### 源石技艺\n\n"
                + "源石技艺（Arts）是利用源石作为媒介施放的特殊能力。\n"
                + "施放者通过自身或法杖中的源石引导能量，效果涵盖攻击、防御、治疗、通讯等多种形式。\n\n"
                + "## 主要势力\n\n"
                + "### 罗德岛（Rhodes Island）\n"
                + "表面为制药公司，实际致力于感染者权益和矿石病治疗，拥有准军事力量。\n\n"
                + "### 整合运动（Reunion）\n"
                + "感染者激进组织，主张以暴力手段推翻对感染者的压迫。\n\n"
                + "## 国家/地区\n\n"
                + "- 乌萨斯（Ursus）— 北方军事帝国，对感染者采取高压政策\n"
                + "- 龙门（Lungmen）— 独立移动城邦，商业繁荣，与罗德岛有合作\n"
                + "- 炎国（Yan）— 东方大国，历史悠久的中央集权帝国\n"
                + "- 维多利亚（Victoria）— 西方工业强国，正处于王位继承危机\n"
                + "- 莱塔尼亚（Leithanien）— 以源石技艺闻名的贵族制国家\n"
                + "- 卡西米尔（Kazimierz）— 骑士竞技文化盛行的商业国家\n"
                + "- 哥伦比亚（Columbia）— 新兴民主共和国，科技与商业发展迅速\n"
                + "- 拉特兰（Laterano）— 宗教圣地，萨科塔族（天使）的国度\n\n"
                + "## 资料状态\n\n"
                + "- 以上专有名词和设定为本地 fallback 生成，未从 wiki 导入，需后续核验\n"
                + "- 人物卡、玩家档案、具体时间线：待导入/待录入\n";
    }

    /** Arknights/Terra 专用 deterministic fallback: entities.md。 */
    private String buildArknightsEntitiesMd() {
        return "# 实体设定\n\n"
                + "## 罗德岛\n\n"
                + "- 阿米娅（Amiya）— 罗德岛公开领导人，卡特斯族，源石技艺适应性卓越\n"
                + "- 博士（Doctor）— 罗德岛战术指挥官，具体背景待核验\n"
                + "- 凯尔希（Kal'tsit）— 罗德岛医疗部负责人，背景深不可测\n\n"
                + "## 整合运动\n\n"
                + "- 塔露拉（Talulah）— 整合运动领袖，德拉克族，源石技艺极为强大\n\n"
                + "## 国家/势力代表（待补全）\n\n"
                + "- 乌萨斯：待核验\n"
                + "- 龙门：待核验\n"
                + "- 炎国：待核验\n"
                + "- 维多利亚：待核验\n"
                + "- 莱塔尼亚：待核验\n"
                + "- 卡西米尔：待核验\n"
                + "- 哥伦比亚：待核验\n"
                + "- 拉特兰：待核验\n\n"
                + "## 人物卡\n\n"
                + "待录入。可使用 /import url 导入 wiki 资料后补全。\n";
    }

    // Keep old fallback methods for general (non-Arknights) content below.
    // buildFallbackWorldMd / buildFallbackEntitiesMd / buildFallbackRulesMd / buildFallbackPlayersMd

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
                + "## 核心矛盾\n\n- 待用户补充\n\n"
                + "## 资料状态\n\n"
                + "- 世界观资料：基础模板，未从 wiki 导入，需后续核验\n"
                + "- 国家与势力：待补全\n"
                + "- 人物卡：待录入\n"
                + "- 玩家资料：待录入\n";
    }

    private String buildFallbackEntitiesMd() {
        return "# 实体设定\n\n"
                + "## 势力与组织\n\n待补全。可使用 /import url 导入 wiki 资料后补全。\n\n"
                + "## 人物卡\n\n待录入。\n";
    }

    private String buildFallbackRulesMd() {
        return "# 推演规则\n\n"
                + "## 设定可信度分级\n\n"
                + "- **已核验** — 经 wiki 导入或官方来源确认\n"
                + "- **待核验** — 本地生成或用户提供，尚未交叉验证\n"
                + "- **推演假设** — 推演过程中产生的推断，非确定事实\n\n"
                + "## 资料写入规则\n\n"
                + "- 未核验资料必须标记为待核验\n"
                + "- 玩家行动应写入 branch 文件的 PlayerAction 记录区（player_action_append）\n"
                + "- 短推演内容应写入 SimulationContent（simulation_content_append）\n"
                + "- 回合总结写入 TurnSettlement（turn_settlement_save）\n"
                + "- 长期人物资料写入 root players.md（root_players_update）\n"
                + "- **本回合行动不得写入 players.md**\n\n"
                + "## 基础规则\n\n"
                + "- 本 root 使用用户指定的基础设定。\n"
                + "- 未经确认的具体设定应标记为待核验。\n"
                + "- 世界观根设定只能在根节点修改。\n"
                + "- 根文件补充资料默认 append，覆盖必须 confirmReplace=true。\n";
    }

    private String buildFallbackPlayersMd() {
        return "# 玩家档案\n\n"
                + "本文件记录当前世界中的玩家角色、玩家势力、公开目标、隐藏倾向、资源、关系与当前状态。\n"
                + "玩家本回合行动不要写在这里，行动应写入 branch 的 PlayerAction 记录区。\n"
                + "推演后的玩家状态变化应优先写入 embDB（knowledge_upsert 带 branchId + targetKey）。\n\n"
                + "## 玩家资料区\n\n"
                + "（玩家基本信息：名称、代号、身份、所属势力等）\n\n"
                + "暂无录入。\n\n"
                + "## 人物卡区\n\n"
                + "（详细人物卡：种族、职业、技能、背景故事等）\n\n"
                + "暂无录入。\n\n"
                + "## 长期状态区\n\n"
                + "（跨回合持续的状态：伤势、资源、声望、关系等）\n\n"
                + "暂无录入。\n\n"
                + "## 关系与资源区\n\n"
                + "（与其他角色/势力的关系、拥有的资源与道具）\n\n"
                + "暂无录入。\n";
    }

    private static String truncate(String s, int maxLen) {
        if (s == null || s.isBlank()) return "";
        if (s.length() <= maxLen) return s;
        return s.substring(0, maxLen - 3) + "...";
    }
}
