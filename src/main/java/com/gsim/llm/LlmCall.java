package com.gsim.llm;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * 一个在途的 LLM 调用句柄。
 *
 * <p>Agent 通过 {@link LlmManager#submit(LlmRequest)} 获取 LlmCall，
 * 然后可以实时读取 {@link #pool()} 中的流式内容，或阻塞等待 {@link #await(long)} 获取最终结果。
 *
 * <p>线程安全。
 */
public class LlmCall {

    private final String id;
    private final StreamPool pool;
    private volatile boolean cancelled = false;

    public LlmCall(String id, StreamPool pool) {
        this.id = id;
        this.pool = pool;
    }

    /** 唯一标识。 */
    public String id() { return id; }

    /** 流式事件池 — 实时可读。 */
    public StreamPool pool() { return pool; }

    /**
     * 阻塞等待调用完成，返回最终结果。
     * @param timeoutMs 超时毫秒数
     * @return LlmResult（success=false + errorMessage 表示失败）
     * @throws InterruptedException 线程被中断
     * @throws TimeoutException 超时
     */
    public LlmResult await(long timeoutMs) throws InterruptedException, TimeoutException {
        boolean done = pool.awaitCompletion(timeoutMs);
        if (!done) {
            throw new TimeoutException("LlmCall[" + id + "] timed out after " + timeoutMs + "ms");
        }
        LlmResult result = pool.getFinalResult();
        if (result == null) {
            return LlmResult.failure("No result from LlmCall[" + id + "]");
        }
        return result;
    }

    /** 取消调用。 */
    public void cancel() {
        cancelled = true;
    }

    /** 是否已被取消。 */
    public boolean isCancelled() { return cancelled; }

    /** 调用是否已结束（成功或失败）。 */
    public boolean isDone() { return pool.isComplete(); }

    @Override
    public String toString() {
        return "LlmCall{id=" + id + ", done=" + isDone() + ", cancelled=" + cancelled + "}";
    }
}
