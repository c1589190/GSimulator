package com.gsim.llm;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 单个 LLM Provider 配置 — 从 llms.json 条目解析。
 *
 * <p>支持 {@code ${ENV_VAR}} 环境变量引用（在 apiKey 中）。
 * 可转换为 ProviderConfig 供 {@link Provider} 使用。
 */
public record LlmConfig(
        String id,
        String name,
        String baseUrl,
        String apiKey,
        String model,
        double defaultTemperature,
        int defaultMaxTokens,
        Map<String, Object> extraBody,
        Map<String, Object> thinking,
        boolean isDefault
) {
    private static final Pattern ENV_PATTERN = Pattern.compile("\\$\\{([^}]+)\\}");

    /** 从 llms.json 的单个 provider 节点解析。 */
    public static LlmConfig fromJson(JsonNode node) {
        String id = node.path("id").asText("");
        if (id.isBlank()) throw new IllegalArgumentException("LLM provider missing 'id'");

        String name = node.path("name").asText(id);
        String baseUrl = node.path("baseUrl").asText("");
        if (baseUrl.isBlank()) throw new IllegalArgumentException("LLM provider '" + id + "' missing 'baseUrl'");

        String apiKey = resolveEnv(node.path("apiKey").asText(""));
        String model = node.path("model").asText("");
        if (model.isBlank()) throw new IllegalArgumentException("LLM provider '" + id + "' missing 'model'");

        double temp = node.path("defaultTemperature").asDouble(0.3);
        int maxTok = node.path("defaultMaxTokens").asInt(4096);

        Map<String, Object> extraBody = null;
        JsonNode ebNode = node.path("extraBody");
        if (ebNode != null && ebNode.isObject() && !ebNode.isEmpty()) {
            extraBody = jsonNodeToMap(ebNode);
        }

        Map<String, Object> thinking = null;
        JsonNode thNode = node.path("thinking");
        if (thNode != null && thNode.isObject() && !thNode.isEmpty()) {
            thinking = jsonNodeToMap(thNode);
        }

        boolean isDefault = node.path("default").asBoolean(false);

        return new LlmConfig(id, name, baseUrl, apiKey, model, temp, maxTok,
                extraBody, thinking, isDefault);
    }

    /** 转换为 ProviderConfig（用于 Provider/LlmManager 构造）。 */
    public ProviderConfig toProviderConfig() {
        return new ProviderConfig(
                name,
                baseUrl,
                apiKey,
                model,
                defaultTemperature,
                120, // timeout seconds
                true,  // supportsForcedToolChoice
                thinking != null, // hasNativeReasoning (heuristic: if thinking config present, provider may support it)
                extraBody,
                thinking
        );
    }

    /** 脱敏描述，不打印 apiKey。 */
    public String toSafeString() {
        return "LlmConfig{id=" + id + ", name=" + name + ", baseUrl=" + baseUrl
                + ", model=" + model + ", temp=" + defaultTemperature
                + ", maxTokens=" + defaultMaxTokens
                + ", isDefault=" + isDefault + "}";
    }

    // ---- helpers ----

    /** 解析字符串中的 ${ENV_VAR} 引用。 */
    static String resolveEnv(String value) {
        if (value == null || value.isBlank()) return value;
        Matcher m = ENV_PATTERN.matcher(value);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            String envName = m.group(1);
            String envValue = System.getenv(envName);
            if (envValue == null) envValue = "";
            m.appendReplacement(sb, Matcher.quoteReplacement(envValue));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> jsonNodeToMap(JsonNode node) {
        try {
            com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
            return mapper.treeToValue(node, Map.class);
        } catch (Exception e) {
            return Collections.emptyMap();
        }
    }
}
