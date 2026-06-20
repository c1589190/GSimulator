package com.gsim.llm;

import java.util.List;

/**
 * LLM 调用最终结果 — LlmManager 的唯一产出。
 *
 * <p>替代旧 {@code LlmResponse}：字段语义不变，但构造方式完全由 LlmManager 内部控制。
 * Agent 只读不写。
 */
public record LlmResult(
        String content,
        String reasoning,
        String model,
        int tokensUsed,
        boolean success,
        String errorMessage,
        List<LlmToolCall> toolCalls,
        String finishReason,
        boolean contextLengthExceeded
) {
    /** 成功响应（无 tool_calls）。 */
    public static LlmResult success(String content, String model, int tokensUsed) {
        return new LlmResult(content, "", model, tokensUsed, true, null, List.of(), "stop", false);
    }

    /** 成功响应（含 reasoning）。 */
    public static LlmResult successWithReasoning(String content, String reasoning, String model, int tokensUsed) {
        return new LlmResult(content, reasoning != null ? reasoning : "", model, tokensUsed, true, null, List.of(), "stop", false);
    }

    /** 成功响应（含 API tool_calls）。 */
    public static LlmResult withToolCalls(List<LlmToolCall> toolCalls, String model, int tokensUsed) {
        return new LlmResult(null, "", model, tokensUsed, true, null,
                toolCalls != null ? List.copyOf(toolCalls) : List.of(), "tool_calls", false);
    }

    /** 失败响应。 */
    public static LlmResult failure(String errorMessage) {
        return new LlmResult(null, "", null, 0, false, errorMessage, List.of(), null, false);
    }

    /** 上下文长度超限错误。 */
    public static LlmResult contextLengthExceeded(String errorMessage) {
        return new LlmResult(null, "", null, 0, false, errorMessage, List.of(), null, true);
    }

    /** 是否有 API 原生 tool_calls。 */
    public boolean hasApiToolCalls() {
        return toolCalls != null && !toolCalls.isEmpty();
    }

    /** content 是否为有意义的文本（非 null / 非空 / 非占位符）。 */
    public boolean hasContent() {
        return content != null && !content.isBlank();
    }

    /** 是否因上下文长度超限而失败。 */
    public boolean isContextLengthExceeded() {
        return contextLengthExceeded;
    }
}
