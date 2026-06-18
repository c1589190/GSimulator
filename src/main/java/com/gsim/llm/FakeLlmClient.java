package com.gsim.llm;

import java.util.ArrayList;
import java.util.List;

/**
 * 假的 LLM 客户端，用于测试和离线开发。
 * 支持预设单次响应或响应序列，并提供消息捕获供测试检查。
 */
public class FakeLlmClient implements LlmClient {

    private final List<String> responses = new ArrayList<>();
    private int callCount = 0;
    private String defaultResponse = "{}";
    private boolean available;

    /** 所有通过 chat() 发送的请求记录，供测试检查 system prompt 内容。 */
    private final List<LlmRequest> capturedRequests = new ArrayList<>();

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
        capturedRequests.add(request);
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

    /** 返回所有捕获的 LLM 请求。 */
    public List<LlmRequest> getCapturedRequests() {
        return List.copyOf(capturedRequests);
    }

    /** 返回最后一次请求的 system 消息内容（合并所有 system 消息）。 */
    public String getLastSystemPrompt() {
        if (capturedRequests.isEmpty()) return "";
        LlmRequest last = capturedRequests.get(capturedRequests.size() - 1);
        StringBuilder sb = new StringBuilder();
        for (LlmMessage msg : last.messages()) {
            if ("system".equals(msg.role())) {
                if (!sb.isEmpty()) sb.append("\n");
                sb.append(msg.content());
            }
        }
        return sb.toString();
    }

    /** 返回所有 system 消息内容（所有请求）。 */
    public List<String> getAllSystemPrompts() {
        List<String> result = new ArrayList<>();
        for (LlmRequest req : capturedRequests) {
            StringBuilder sb = new StringBuilder();
            for (LlmMessage msg : req.messages()) {
                if ("system".equals(msg.role())) {
                    if (!sb.isEmpty()) sb.append("\n");
                    sb.append(msg.content());
                }
            }
            result.add(sb.toString());
        }
        return result;
    }

    /** 清除捕获的请求和响应状态。 */
    public void reset() {
        capturedRequests.clear();
        responses.clear();
        callCount = 0;
        defaultResponse = "{}";
    }

    @Override
    public boolean isAvailable() {
        return available;
    }
}
