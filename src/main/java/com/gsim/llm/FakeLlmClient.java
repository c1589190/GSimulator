package com.gsim.llm;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 假的 LLM 客户端，用于测试和离线开发。
 * 支持预设单次响应或响应序列，并提供消息捕获供测试检查。
 * 支持模拟 API tool_calls 响应。
 */
public class FakeLlmClient implements LlmClient {

    private final List<String> responses = new ArrayList<>();
    private final List<List<LlmToolCall>> toolCallsResponses = new ArrayList<>();
    private int callCount = 0;
    private String defaultResponse = "{}";
    private List<LlmToolCall> defaultToolCalls = List.of();
    private boolean available;

    /** 所有通过 chat() 发送的请求记录，供测试检查 system prompt 内容。 */
    private final List<LlmRequest> capturedRequests = new ArrayList<>();

    public FakeLlmClient() {
        this.available = true;
    }

    /** 预设下一次调用的返回内容（覆盖 defaultResponse）。 */
    public void setNextResponse(String response) {
        this.defaultResponse = response;
        this.defaultToolCalls = List.of();
    }

    /** 添加一个按顺序的文本响应。 */
    public void addResponse(String response) {
        this.responses.add(response);
        this.toolCallsResponses.add(List.of());
    }

    /** 添加一个按顺序的 API tool_calls 响应（content 为 null）。 */
    public void addToolCallsResponse(List<LlmToolCall> toolCalls) {
        this.responses.add("");
        this.toolCallsResponses.add(toolCalls != null ? toolCalls : List.of());
    }

    /** 添加一个按顺序的响应（带 tool_calls 和可选 content）。 */
    public void addResponse(String content, List<LlmToolCall> toolCalls) {
        this.responses.add(content != null ? content : "");
        this.toolCallsResponses.add(toolCalls != null ? toolCalls : List.of());
    }

    /** 设置是否可用（模拟 LLM 不可用的情况）。 */
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
        List<LlmToolCall> toolCalls;
        if (callCount < responses.size()) {
            content = responses.get(callCount);
            toolCalls = callCount < toolCallsResponses.size()
                    ? toolCallsResponses.get(callCount) : List.of();
        } else {
            content = defaultResponse;
            toolCalls = defaultToolCalls;
        }
        callCount++;

        if (!toolCalls.isEmpty()) {
            return LlmResponse.successWithToolCalls(toolCalls, "fake-model", content.length());
        }
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

    /** 返回已捕获请求中最后一次请求的 tools。 */
    public List<ToolDef> getLastTools() {
        if (capturedRequests.isEmpty()) return List.of();
        LlmRequest last = capturedRequests.get(capturedRequests.size() - 1);
        return last.tools() != null ? last.tools() : List.of();
    }

    /** 清除捕获的请求和响应状态。 */
    public void reset() {
        capturedRequests.clear();
        responses.clear();
        toolCallsResponses.clear();
        callCount = 0;
        defaultResponse = "{}";
        defaultToolCalls = List.of();
    }

    @Override
    public boolean isAvailable() {
        return available;
    }
}
