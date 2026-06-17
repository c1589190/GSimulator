package com.gsim.llm;

import java.util.List;

/**
 * LLM 请求。
 */
public record LlmRequest(
        String model,
        List<LlmMessage> messages,
        double temperature,
        int maxTokens
) {
}
