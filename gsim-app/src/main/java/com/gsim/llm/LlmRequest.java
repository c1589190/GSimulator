package com.gsim.llm;

import java.util.List;
import java.util.Map;

/**
 * LLM 请求。
 */
public record LlmRequest(
        String model,
        List<LlmMessage> messages,
        double temperature,
        int maxTokens,
        List<ToolDef> tools,
        Object toolChoice,   // null | "auto" | "none" | Map<String,Object> forced tool
        Map<String, Object> extraBody,  // 请求级扩展参数（与 ProviderConfig.extraBody 合并）
        Map<String, Object> thinking    // 请求级 thinking 参数（覆盖 ProviderConfig.thinking）
) {
    /** 向后兼容：不传 tools 和 tool_choice。 */
    public LlmRequest(String model, List<LlmMessage> messages, double temperature, int maxTokens) {
        this(model, messages, temperature, maxTokens, List.of(), null, null, null);
    }

    /** 带 tools 的请求，tool_choice 默认 "auto"。 */
    public LlmRequest(String model, List<LlmMessage> messages, double temperature,
                      int maxTokens, List<ToolDef> tools) {
        this(model, messages, temperature, maxTokens, tools,
                tools != null && !tools.isEmpty() ? "auto" : null, null, null);
    }

    /** 带 tools + tool_choice 的请求（旧版兼容）。 */
    public LlmRequest(String model, List<LlmMessage> messages, double temperature,
                      int maxTokens, List<ToolDef> tools, Object toolChoice) {
        this(model, messages, temperature, maxTokens, tools, toolChoice, null, null);
    }
}
