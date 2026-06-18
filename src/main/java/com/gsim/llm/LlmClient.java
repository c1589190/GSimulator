package com.gsim.llm;

/**
 * LLM 客户端统一接口。
 * 所有 LLM 调用必须通过此接口，不允许业务代码直接拼 HTTP 请求。
 */
public interface LlmClient {

    /**
     * 发送聊天请求并获取回复文本（非流式）。
     */
    LlmResponse chat(LlmRequest request);

    /**
     * 流式聊天请求。
     * 通过 {@link LlmStreamListener} 回调接收增量输出。
     *
     * <p>默认实现退化到非流式 {@link #chat}。
     * 实现类应覆盖此方法以支持真正的流式传输。
     *
     * @param request  请求
     * @param listener 流式回调
     */
    default void stream(LlmRequest request, LlmStreamListener listener) {
        try {
            listener.onStart();
            LlmResponse response = chat(request);
            if (response.success()) {
                listener.onContentDelta(response.content());
            } else {
                listener.onError(new RuntimeException(response.errorMessage()));
            }
            listener.onComplete();
        } catch (Exception e) {
            listener.onError(e);
        }
    }

    /**
     * 检查 LLM 客户端是否就绪。
     */
    boolean isAvailable();
}
