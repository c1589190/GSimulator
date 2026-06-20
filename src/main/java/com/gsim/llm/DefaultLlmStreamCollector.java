package com.gsim.llm;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

/**
 * LlmStreamCollector 的默认实现。
 * 线程安全地收集流式 delta 并组装最终 LlmResponse。
 */
public class DefaultLlmStreamCollector implements LlmStreamCollector {

    private final StringBuilder contentBuf = new StringBuilder();
    private final StringBuilder reasoningBuf = new StringBuilder();
    private final AtomicReference<LlmResponse> finalResponse = new AtomicReference<>();
    private volatile List<LlmToolCall> toolCalls = List.of();
    private volatile String reasoning = "";

    @Override
    public void onContentDelta(String text) {
        synchronized (contentBuf) {
            contentBuf.append(text);
        }
    }

    @Override
    public void onReasoningDelta(String text) {
        synchronized (reasoningBuf) {
            reasoningBuf.append(text);
        }
    }

    @Override
    public void onToolCallDelta(String text) {
        // tool_calls 不收集到 content/reasoning，由 setToolCalls 设置
    }

    @Override
    public void setFinalResponse(LlmResponse response) {
        finalResponse.set(response);
    }

    @Override
    public LlmResponse getFinalResponse() {
        return finalResponse.get();
    }

    @Override
    public void setReasoning(String reasoning) {
        this.reasoning = reasoning != null ? reasoning : "";
    }

    @Override
    public String getReasoning() {
        // 优先返回显式设置的值，否则返回缓冲区内容
        if (reasoning != null && !reasoning.isEmpty()) {
            return reasoning;
        }
        synchronized (reasoningBuf) {
            return reasoningBuf.toString();
        }
    }

    @Override
    public void setToolCalls(List<LlmToolCall> toolCalls) {
        this.toolCalls = toolCalls != null ? List.copyOf(toolCalls) : List.of();
    }

    @Override
    public List<LlmToolCall> getToolCalls() {
        return toolCalls;
    }

    @Override
    public String getFullContent() {
        synchronized (contentBuf) {
            return contentBuf.toString();
        }
    }
}
