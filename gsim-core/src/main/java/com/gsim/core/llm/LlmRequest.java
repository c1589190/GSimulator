package com.gsim.core.llm;

import java.util.List;
import java.util.Map;

/**
 * LLM 请求 — 封装发送给 LLM API 的完整请求参数。
 *
 * <p>包含模型选择、消息列表、温度参数、工具定义等。
 * 支持向后兼容的紧凑构造器和扩展参数（extraBody、thinking）。
 *
 * @param model       模型名称（如 "deepseek-chat"）
 * @param messages    消息列表（system/user/assistant/tool 角色）
 * @param temperature 采样温度（0.0 ~ 2.0）
 * @param maxTokens   最大输出 token 数
 * @param tools       工具定义列表（用于 function calling）
 * @param toolChoice  工具选择策略：null | "auto" | "none" | Map&lt;String,Object&gt;
 * @param extraBody   请求级扩展参数（与 ProviderConfig.extraBody 合并）
 * @param thinking    请求级 thinking 参数（覆盖 ProviderConfig.thinking）
 */
public record LlmRequest(
        String model,
        List<LlmMessage> messages,
        double temperature,
        int maxTokens,
        List<ToolDef> tools,
        Object toolChoice, // null | "auto" | "none" | Map<String,Object> forced tool
        Map<String, Object> extraBody, // 请求级扩展参数（与 ProviderConfig.extraBody 合并）
        Map<String, Object> thinking // 请求级 thinking 参数（覆盖 ProviderConfig.thinking）
        ) {
    /** 向后兼容：不传 tools 和 tool_choice。 */
    public LlmRequest(String model, List<LlmMessage> messages, double temperature, int maxTokens) {
        this(model, messages, temperature, maxTokens, List.of(), null, null, null);
    }

    /** 带 tools 的请求，tool_choice 默认 "auto"。 */
    public LlmRequest(String model, List<LlmMessage> messages, double temperature, int maxTokens, List<ToolDef> tools) {
        this(
                model,
                messages,
                temperature,
                maxTokens,
                tools,
                tools != null && !tools.isEmpty() ? "auto" : null,
                null,
                null);
    }

    /** 带 tools + tool_choice 的请求（旧版兼容）。 */
    public LlmRequest(
            String model,
            List<LlmMessage> messages,
            double temperature,
            int maxTokens,
            List<ToolDef> tools,
            Object toolChoice) {
        this(model, messages, temperature, maxTokens, tools, toolChoice, null, null);
    }
}
