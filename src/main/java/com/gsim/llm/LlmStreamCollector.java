package com.gsim.llm;

import java.util.List;

/**
 * 扩展 LlmStreamListener — 用于收集流式输出并组装最终 LlmResponse。
 *
 * <p>OpenAiCompatibleLlmClient.stream() 在完成流式传输后，
 * 会通过此接口设置最终响应，供调用方获取完整的 content / reasoning / tool_calls。
 */
public interface LlmStreamCollector extends LlmStreamListener {

    /**
     * 设置流式传输完成后组装的最终响应。
     */
    void setFinalResponse(LlmResponse response);

    /**
     * 获取最终响应（流式完成后）。
     */
    LlmResponse getFinalResponse();

    /**
     * 设置完整的 reasoning 文本。
     */
    void setReasoning(String reasoning);

    /**
     * 获取完整的 reasoning 文本。
     */
    String getReasoning();

    /**
     * 设置组装的 tool_calls 列表。
     */
    void setToolCalls(List<LlmToolCall> toolCalls);

    /**
     * 获取组装的 tool_calls 列表。
     */
    List<LlmToolCall> getToolCalls();

    /**
     * 获取完整的 content 文本。
     */
    String getFullContent();
}
