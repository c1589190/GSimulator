package com.gsim.agent.core;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;

/**
 * Agent 配置 — 驱动 Agent 创建的所有参数。
 *
 * <p>可从 classpath JSON 或 Map 构建。systemPrompt / userTemplate
 * 可以是 classpath 路径（以 "gsim/" 开头）或 inline 文本。
 */
public record AgentConfig(
        String agentId,
        String systemPrompt,
        String userTemplate,
        ToolFilterConfig toolFilter,
        int maxToolRounds,
        double temperature,
        int maxTokens
) {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** 默认主 Agent 配置 */
    public static AgentConfig defaultOrchestrator() {
        return new AgentConfig("orchestrator", "", "", ToolFilterConfig.ALL, 32, 0.3, 2048);
    }

    /** 从 classpath JSON 文件加载 */
    public static AgentConfig fromClasspath(String classpath) throws IOException {
        try (InputStream is = Thread.currentThread().getContextClassLoader()
                .getResourceAsStream(classpath)) {
            if (is == null) throw new IOException("AgentConfig not found: " + classpath);
            String raw = new String(is.readAllBytes(), StandardCharsets.UTF_8);
            return fromJson(raw);
        }
    }

    /** 从 JSON 字符串解析 */
    public static AgentConfig fromJson(String json) throws IOException {
        var node = MAPPER.readTree(json);
        String agentId = node.path("agentId").asText("unknown");
        String systemPrompt = node.path("systemPrompt").asText("");
        String userTemplate = node.path("userTemplate").asText("");
        String toolMode = node.path("toolFilter").path("mode").asText("all");
        var allow = node.path("toolFilter").path("allow");
        var deny = node.path("toolFilter").path("deny");
        var allowList = allow.isArray()
                ? new java.util.ArrayList<String>() {{ for (var n : allow) add(n.asText()); }}
                : java.util.List.<String>of();
        var denyList = deny.isArray()
                ? new java.util.ArrayList<String>() {{ for (var n : deny) add(n.asText()); }}
                : java.util.List.<String>of();
        var filter = new ToolFilterConfig(toolMode, allowList, denyList);
        int maxRounds = node.path("maxToolRounds").asInt(32);
        double temp = node.path("temperature").asDouble(0.3);
        int maxTok = node.path("maxTokens").asInt(2048);
        return new AgentConfig(agentId, systemPrompt, userTemplate, filter, maxRounds, temp, maxTok);
    }

    /** 渲染 user prompt（替换 {{变量}}） */
    public String renderUserPrompt(Map<String, String> vars) {
        if (userTemplate == null || userTemplate.isBlank()) return "";
        String t = userTemplate;
        for (var e : vars.entrySet()) {
            t = t.replace("{{" + e.getKey() + "}}", e.getValue() != null ? e.getValue() : "");
        }
        return t;
    }
}
