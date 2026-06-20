package com.gsim.llm;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * LLM 管理器 — Agent 与 LLM Provider 之间的唯一入口。
 *
 * <h3>核心方法</h3>
 * <ul>
 *   <li>{@link #submit(LlmRequest)} — 异步流式调用，立即返回 {@link LlmCall}，delta 实时进 {@link StreamPool}</li>
 *   <li>{@link #chat(LlmRequest)} — 同步非流式调用，阻塞等待完整 {@link LlmResult}</li>
 *   <li>{@link #isAvailable()} — 检查 provider 连通性</li>
 * </ul>
 *
 * <h3>Agent 用法</h3>
 * <pre>{@code
 *   LlmCall call = llmManager.submit(request);
 *   // 实时轮询: call.pool().getContent()
 *   LlmResult result = call.await(60_000);
 *   if (result.hasApiToolCalls()) { ... }
 * }</pre>
 *
 * <p>线程安全。支持多 Agent 并发调用。
 */
public class LlmManager {

    private static final Logger log = LoggerFactory.getLogger(LlmManager.class);

    private final Provider provider;
    private final AtomicInteger activeCalls = new AtomicInteger(0);

    /**
     * Protected 无参构造器 — 供测试 fake 子类使用。
     * provider 为 null，子类必须覆盖 chat/submit/isAvailable。
     */
    protected LlmManager() {
        this.provider = null;
    }

    /**
     * 从 ProviderConfig 创建 LlmManager。
     */
    public LlmManager(ProviderConfig config) {
        this.provider = new Provider(config);
        log.info("[LlmManager] initialized: {}", config.toSafeString());
    }

    /**
     * 异步流式调用。
     * 立即返回 {@link LlmCall}，在后台虚拟线程中执行 HTTP 请求 + SSE 解析。
     * delta 实时写入 {@link StreamPool}，Agent 可随时读取。
     */
    public LlmCall submit(LlmRequest request) {
        String callId = UUID.randomUUID().toString();
        StreamPool pool = new StreamPool(callId);
        LlmCall call = new LlmCall(callId, pool);

        activeCalls.incrementAndGet();
        provider.stream(request, pool);

        // 当 pool 完成时减少计数
        Thread.startVirtualThread(() -> {
            try {
                pool.awaitCompletion(300_000); // 5 min max
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                activeCalls.decrementAndGet();
            }
        });

        return call;
    }

    /**
     * 同步非流式调用。
     * 阻塞等待完整响应，返回 {@link LlmResult}。
     */
    public LlmResult chat(LlmRequest request) {
        try {
            return provider.chat(request);
        } catch (IOException e) {
            log.error("[LlmManager] chat failed: {}", e.getMessage(), e);
            return LlmResult.failure(e.getMessage());
        }
    }

    /** 检查 provider 是否可用（发 ping 请求）。 */
    public boolean isAvailable() {
        try {
            return provider.isAvailable();
        } catch (Exception e) {
            return false;
        }
    }

    /** 当前活跃的流式调用数。 */
    public int activeCallCount() {
        return activeCalls.get();
    }

    /** 获取 provider 配置（只读）。 */
    public ProviderConfig getConfig() {
        return provider.config();
    }

    /** 关闭底层 HTTP 资源。 */
    public void close() {
        provider.close();
    }
}
