package com.gsim.llm;

/**
 * 假的 LLM 客户端，用于测试和离线开发。
 * 可通过 setNextResponse 预设返回内容。
 */
public class FakeLlmClient implements LlmClient {

    private String nextResponse;
    private boolean available;

    public FakeLlmClient() {
        this.nextResponse = "{\"message\": \"This is a fake LLM response.\"}";
        this.available = true;
    }

    /**
     * 预设下一次调用的返回内容。
     */
    public void setNextResponse(String response) {
        this.nextResponse = response;
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
        return LlmResponse.success(nextResponse, "fake-model", nextResponse.length());
    }

    @Override
    public boolean isAvailable() {
        return available;
    }
}
