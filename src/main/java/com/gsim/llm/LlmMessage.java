package com.gsim.llm;

/**
 * LLM 消息。
 */
public record LlmMessage(
        String role,    // system, user, assistant, tool
        String content
) {
    public static LlmMessage system(String content) {
        return new LlmMessage("system", content);
    }

    public static LlmMessage user(String content) {
        return new LlmMessage("user", content);
    }

    public static LlmMessage assistant(String content) {
        return new LlmMessage("assistant", content);
    }

    public static LlmMessage tool(String content) {
        return new LlmMessage("tool", content);
    }
}
