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
 * <p>可从 classpath JSON 或 filesystem agents/{id}.json 加载。
 *
 * <h3>Prompt 结构</h3>
 * <ul>
 *   <li>{@code staticSystemPrompt} — 静态系统提示词，直接定义 Agent 的行为边界。</li>
 *   <li>{@code systemPrompt} — 兼容旧字段（staticSystemPrompt 为空时使用）。</li>
 * </ul>
 * 系统提示词 = staticSystemPrompt（优先）或 systemPrompt（回退）。
 * 不再使用 FreeMarker 动态渲染 — 全部内容直接存储在 JSON 中。
 */
public record AgentConfig(
        String agentId,
        String llmProvider,
        String staticSystemPrompt,
        String systemPrompt,        // 兼容旧字段
        String userTemplate,
        ToolFilterConfig toolFilter,
        int maxToolRounds,
        double temperature,
        int maxTokens
) {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** 默认主 Agent 配置 */
    public static AgentConfig defaultOrchestrator() {
        return new AgentConfig("orchestrator", "base", "", "",
                "", ToolFilterConfig.ALL, 32, 0.3, 2048);
    }

    // ---- 兼容工厂方法 ----

    /** 旧版构造（不含 llmProvider / staticSystemPrompt）。 */
    public static AgentConfig of(String agentId, String systemPrompt, String userTemplate,
                                  ToolFilterConfig toolFilter, int maxToolRounds,
                                  double temperature, int maxTokens) {
        return new AgentConfig(agentId, "base", systemPrompt, systemPrompt,
                userTemplate, toolFilter, maxToolRounds, temperature, maxTokens);
    }

    // ---- 加载方法 ----

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
        String llmProvider = node.path("llmProvider").asText("base");
        String staticSys = node.path("staticSystemPrompt").asText("");
        // 兼容旧字段 systemPrompt（staticSystemPrompt 为空时回退）
        String sysPrompt = node.path("systemPrompt").asText("");
        if (!staticSys.isBlank()) {
            sysPrompt = staticSys;
        } else if (sysPrompt.isBlank()) {
            sysPrompt = staticSys; // both empty
        } else {
            // systemPrompt has content, staticSystemPrompt is empty — use systemPrompt
        }

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

        return new AgentConfig(agentId, llmProvider, staticSys,
                sysPrompt, userTemplate, filter, maxRounds, temp, maxTok);
    }

    // ---- 工具方法 ----

    /** 获取完整的系统提示词。staticSystemPrompt 优先，回退到 systemPrompt。 */
    public String fullSystemPrompt() {
        return (staticSystemPrompt != null && !staticSystemPrompt.isBlank())
                ? staticSystemPrompt : systemPrompt;
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
