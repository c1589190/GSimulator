package com.gsim.llm;

import com.gsim.util.JsonUtils;

/**
 * JSON LLM 服务 — 调用 LLM 并要求返回结构化 JSON。
 * 解析失败时自动 repair 一次，第二次失败则抛出异常。
 */
public class JsonLlmService {

    private final LlmManager llmManager;

    public JsonLlmService(LlmManager llmManager) {
        this.llmManager = llmManager;
    }

    /**
     * 调用 LLM 并解析为指定类型的 JSON 对象。
     */
    public <T> T callForJson(LlmRequest request, Class<T> clazz) {
        LlmResult response = llmManager.chat(request);
        if (!response.success()) {
            throw new RuntimeException("LLM call failed: " + response.errorMessage());
        }
        return JsonUtils.fromJsonWithRepair(response.content(), clazz);
    }

    /**
     * 调用 LLM 并发回纯文本（不做 JSON 解析）。
     */
    public String callForText(LlmRequest request) {
        LlmResult response = llmManager.chat(request);
        if (!response.success()) {
            throw new RuntimeException("LLM call failed: " + response.errorMessage());
        }
        return response.content();
    }
}
