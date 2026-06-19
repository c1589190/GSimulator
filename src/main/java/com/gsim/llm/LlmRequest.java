package com.gsim.llm;

import java.util.List;

/**
 * LLM 请求。
 */
public record LlmRequest(
        String model,
        List<LlmMessage> messages,
        double temperature,
        int maxTokens,
        List<ToolDef> tools,
        String toolChoice
) {
    /** 向后兼容：不传 tools 和 tool_choice。 */
    public LlmRequest(String model, List<LlmMessage> messages, double temperature, int maxTokens) {
        this(model, messages, temperature, maxTokens, List.of(), null);
    }

    /** 带 tools 的请求，tool_choice 默认 "auto"。 */
    public LlmRequest(String model, List<LlmMessage> messages, double temperature,
                      int maxTokens, List<ToolDef> tools) {
        this(model, messages, temperature, maxTokens, tools, "auto");
    }
}
