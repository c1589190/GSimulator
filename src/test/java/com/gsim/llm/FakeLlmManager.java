package com.gsim.llm;

import java.util.List;
import java.util.Queue;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 测试专用 FakeLlmManager — 模拟三种 API（讯飞、SiliconFlow、DeepSeek）的返回行为。
 *
 * <h3>用法</h3>
 * <pre>{@code
 *   FakeLlmManager fake = new FakeLlmManager();
 *   fake.addResponse("{\"tool\":\"finish_action\",\"args\":{\"status\":\"success\",\"message\":\"OK\"}}");
 *   OrchestratorAgent agent = new OrchestratorAgent(fake, toolRegistry, "test-model");
 * }</pre>
 *
 * <p>用于 mvn test CI 环境，不依赖任何外部 LLM 服务。
 */
public class FakeLlmManager extends LlmManager {

    private final Queue<LlmResult> responseQueue = new ConcurrentLinkedQueue<>();
    private final List<LlmRequest> capturedRequests = new CopyOnWriteArrayList<>();
    private final String model;
    private volatile boolean available = true;

    public FakeLlmManager() {
        this("fake-model");
    }

    public FakeLlmManager(String model) {
        super();
        this.model = model;
    }

    // ---- Queue responses ----

    /** 队列一个文本响应（成功）。 */
    public void addResponse(String content) {
        responseQueue.add(LlmResult.success(content, model, 0));
    }

    /** 清空队列并设置单个响应（覆盖模式）。 */
    public void setNextResponse(String content) {
        responseQueue.clear();
        responseQueue.add(LlmResult.success(content, model, 0));
    }

    /** 队列一个带 tool_calls 的响应。 */
    public void addToolCallsResponse(List<LlmToolCall> toolCalls) {
        responseQueue.add(LlmResult.withToolCalls(toolCalls, model, 0));
    }

    /** 队列一个带完全控制的响应。 */
    public void addResponse(LlmResult result) {
        responseQueue.add(result);
    }

    /** 队列一个同时包含内容和 API tool_calls 的响应（模拟 LLM 返回文本 + 原生 tool_calls）。 */
    public void addResponse(String text, List<LlmToolCall> toolCalls) {
        responseQueue.add(new LlmResult(text, "", model, 0, true, null,
                toolCalls != null ? List.copyOf(toolCalls) : List.of(), "tool_calls"));
    }

    /** 设置 isAvailable() 的返回值。 */
    public void setAvailable(boolean available) {
        this.available = available;
    }

    // ---- Override LlmManager methods ----

    @Override
    public LlmResult chat(LlmRequest request) {
        capturedRequests.add(request);
        LlmResult result = responseQueue.poll();
        if (result == null) {
            return LlmResult.failure("FakeLlmManager: no queued response");
        }
        return result;
    }

    @Override
    public LlmCall submit(LlmRequest request) {
        capturedRequests.add(request);
        LlmResult result = responseQueue.poll();
        if (result == null) {
            result = LlmResult.failure("FakeLlmManager: no queued response");
        }
        String callId = UUID.randomUUID().toString();
        StreamPool pool = new StreamPool(callId);
        // 预填充 pool 以模拟已完成流
        if (result.content() != null && !result.content().isEmpty()) {
            pool.onContentDelta(result.content());
        }
        if (result.reasoning() != null && !result.reasoning().isEmpty()) {
            pool.onReasoningDelta(result.reasoning());
        }
        if (result.hasApiToolCalls()) {
            for (int i = 0; i < result.toolCalls().size(); i++) {
                var tc = result.toolCalls().get(i);
                pool.onToolCallDelta(i, tc.name(), "{}");
            }
        }
        pool.onComplete(result);
        return new LlmCall(callId, pool);
    }

    @Override
    public boolean isAvailable() {
        return available;
    }

    // ---- Assertion helpers ----

    /** 返回所有被捕获的请求（不可变副本）。 */
    public List<LlmRequest> getCapturedRequests() {
        return List.copyOf(capturedRequests);
    }

    /** 返回已发起的请求总数。 */
    public int getRequestCount() {
        return capturedRequests.size();
    }

    /** 返回最近一次 LLM 请求的 system prompt 内容（第一条 system 消息）。 */
    public String getLastSystemPrompt() {
        if (capturedRequests.isEmpty()) return "";
        LlmRequest last = capturedRequests.get(capturedRequests.size() - 1);
        return last.messages().stream()
                .filter(m -> "system".equals(m.role()))
                .map(LlmMessage::content)
                .findFirst()
                .orElse("");
    }

    /** 清空所有状态。 */
    public void reset() {
        responseQueue.clear();
        capturedRequests.clear();
    }
}
