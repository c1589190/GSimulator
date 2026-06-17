package com.gsim.llm;

import java.util.ArrayList;
import java.util.List;

/**
 * 假的 LLM 客户端，用于测试和离线开发。
 * 支持预设单次响应或响应序列。
 */
public class FakeLlmClient implements LlmClient {

    private final List<String> responses = new ArrayList<>();
    private int callCount = 0;
    private String defaultResponse = "{}";
    private boolean available;

    public FakeLlmClient() {
        this.available = true;
    }

    /**
     * 预设下一次调用的返回内容（覆盖 defaultResponse）。
     */
    public void setNextResponse(String response) {
        this.defaultResponse = response;
    }

    /**
     * 添加一个按顺序的响应。
     */
    public void addResponse(String response) {
        this.responses.add(response);
    }

    /**
     * 设置是否可用（模拟 LLM 不可用的情况）。
     */
    public void setAvailable(boolean available) {
        this.available = available;
    }

    @Override
    public LlmResponse chat(LlmRequest request) {
        if (!available) {
            return LlmResponse.failure("FakeLlmClient is not available");
        }
        String content;
        if (callCount < responses.size()) {
            content = responses.get(callCount);
        } else {
            content = defaultResponse;
        }
        callCount++;
        return LlmResponse.success(content, "fake-model", content.length());
    }

    @Override
    public boolean isAvailable() {
        return available;
    }
}
