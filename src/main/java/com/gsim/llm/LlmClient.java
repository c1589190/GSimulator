package com.gsim.llm;

/**
 * LLM 客户端统一接口。
 * 所有 LLM 调用必须通过此接口，不允许业务代码直接拼 HTTP 请求。
 */
public interface LlmClient {

    /**
     * 发送聊天请求并获取回复文本。
     */
    LlmResponse chat(LlmRequest request);

    /**
     * 检查 LLM 客户端是否就绪。
     */
    boolean isAvailable();
}
