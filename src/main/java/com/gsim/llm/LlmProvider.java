package com.gsim.llm;

/**
 * LLM Provider 接口 — Agent 与 LLM 之间的抽象入口。
 *
 * <p>每个实现封装一个 LLM API 连接（baseUrl + apiKey + model）。
 * 多个 provider 由 {@link LlmProviderRegistry} 管理，Agent 按配置中的 llmProvider 字段选择。
 *
 * <h3>核心方法</h3>
 * <ul>
 *   <li>{@link #submit(LlmRequest)} — 异步流式调用</li>
 *   <li>{@link #chat(LlmRequest)} — 同步非流式调用</li>
 *   <li>{@link #isAvailable()} — 连通性检查</li>
 * </ul>
 *
 * @see LlmManager 默认实现
 * @see LlmProviderRegistry 注册表
 */
public interface LlmProvider {

    /** Provider 唯一标识（对应 llms.json 中的 id）。 */
    String providerId();

    /** 异步流式调用。立即返回 LlmCall，delta 实时进入 StreamPool。 */
    LlmCall submit(LlmRequest request);

    /** 同步非流式调用。阻塞等待完整 LlmResult。 */
    LlmResult chat(LlmRequest request);

    /** 检查 provider 连通性。 */
    boolean isAvailable();

    /** 获取 provider 配置（只读）。 */
    ProviderConfig config();

    /** 关闭底层资源。 */
    void close();
}
