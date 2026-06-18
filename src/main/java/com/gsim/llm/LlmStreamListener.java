package com.gsim.llm;

/**
 * LLM 流式输出监听器。
 *
 * <p>上游 API 返回的 delta 字段映射：
 * <ul>
 *   <li>{@code delta.content} → {@link #onContentDelta(String)}</li>
 *   <li>{@code delta.reasoning_content} → {@link #onReasoningDelta(String)}</li>
 * </ul>
 *
 * <p>注意：不伪造 reasoning。只有上游返回 {@code reasoning_content / thinking} 时才转发，
 * 否则只显示普通 content。
 */
public interface LlmStreamListener {

    /**
     * 流式传输开始。
     */
    default void onStart() {}

    /**
     * 收到 content 增量文本。
     */
    void onContentDelta(String text);

    /**
     * 收到 reasoning / thinking 增量文本。
     * 只有上游 API 确实返回 reasoning_content 时才调用。
     */
    default void onReasoningDelta(String text) {}

    /**
     * 收到工具调用增量文本。
     */
    default void onToolCallDelta(String text) {}

    /**
     * 发生错误。
     */
    default void onError(Throwable error) {}

    /**
     * 流式传输完成。
     */
    default void onComplete() {}
}
