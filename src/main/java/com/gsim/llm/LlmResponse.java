package com.gsim.llm;

import java.util.List;

/**
 * LLM 响应。
 */
public record LlmResponse(
        String content,
        String model,
        int tokensUsed,
        boolean success,
        String errorMessage,
        List<LlmToolCall> toolCalls,
        String finishReason
) {
    /** 成功响应（无 tool_calls）。 */
    public static LlmResponse success(String content, String model, int tokensUsed) {
        return new LlmResponse(content, model, tokensUsed, true, null, List.of(), "stop");
    }

    /** 成功响应（含 API tool_calls）。 */
    public static LlmResponse successWithToolCalls(List<LlmToolCall> toolCalls,
                                                    String model, int tokensUsed) {
        return new LlmResponse(null, model, tokensUsed, true, null, toolCalls, "tool_calls");
    }

    /** 失败响应。 */
    public static LlmResponse failure(String errorMessage) {
        return new LlmResponse(null, null, 0, false, errorMessage, List.of(), null);
    }

    /** 是否有 API 原生 tool_calls。 */
    public boolean hasApiToolCalls() {
        return toolCalls != null && !toolCalls.isEmpty();
    }
}
