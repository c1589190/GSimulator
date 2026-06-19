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
