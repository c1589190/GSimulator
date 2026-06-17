package com.gsim.llm;

/**
 * LLM 响应。
 */
public record LlmResponse(
        String content,
        String model,
        int tokensUsed,
        boolean success,
        String errorMessage
) {
    public static LlmResponse success(String content, String model, int tokensUsed) {
        return new LlmResponse(content, model, tokensUsed, true, null);
    }

    public static LlmResponse failure(String errorMessage) {
        return new LlmResponse(null, null, 0, false, errorMessage);
    }
}
