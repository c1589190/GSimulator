package com.gsim.agentsmanager.llm;

/**
 * LLM 客户端接口 — Agent 运行时与 LLM 提供商之间的契约（B 方案依赖反转）。
 *
 * <p>定义在 agentsmanager，由 gsim-core 的 {@code LlmManager} 实现；
 * Agent 运行时只依赖本接口，不 import core 的具体实现类。
 */
public interface LlmClient {

    /**
     * 异步提交一次 LLM 请求，返回可轮询的调用句柄。
     *
     * @param request 请求（含消息列表、工具定义等）
     * @return 调用句柄（含 {@link LlmCall#pool()} 流式轮询与 {@link LlmCall#await} 阻塞等待）
     */
    LlmCall submit(LlmRequest request);

    /**
     * 同步发起一次 LLM 请求并等待完整结果。
     *
     * @param request 请求
     * @return 完整结果
     */
    LlmResult chat(LlmRequest request);

    /** 提供商 ID（如 "base" / "compact"）。 */
    String providerId();

    /** 提供商是否可用。 */
    boolean isAvailable();
}
