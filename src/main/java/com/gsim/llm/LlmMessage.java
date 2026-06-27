package com.gsim.llm;

import java.util.List;

/**
 * LLM 消息 — 兼容 OpenAI Chat Completions 消息格式。
 *
 * <p>支持标准字段：role, content, tool_calls, tool_call_id, name。
 */
public record LlmMessage(
        String role,                    // system, user, assistant, tool
        String content,
        List<LlmToolCall> toolCalls,    // assistant 消息的 tool_calls（仅 API 原生 tool_call 时非空）
        String toolCallId,              // tool 消息的 tool_call_id
        String name                     // tool 消息的 name（可选，用于区分工具）
) {
    // ── 向后兼容构造器 ──

    /** 最简构造（向后兼容）：role + content，无 tool_calls 信息。 */
    public LlmMessage(String role, String content) {
        this(role, content, null, null, null);
    }

    // ── 工厂方法 ──

    public static LlmMessage system(String content) {
        return new LlmMessage("system", content);
    }

    public static LlmMessage user(String content) {
        return new LlmMessage("user", content);
    }

    /** 普通 assistant 消息（无 tool_calls）。 */
    public static LlmMessage assistant(String content) {
        return new LlmMessage("assistant", content);
    }

    /** assistant 消息 + API 原生 tool_calls。 */
    public static LlmMessage assistantWithToolCalls(String content, List<LlmToolCall> toolCalls) {
        return new LlmMessage("assistant", content,
                toolCalls != null ? List.copyOf(toolCalls) : null,
                null, null);
    }

    /** tool 消息（工具结果反馈），不含 tool_call_id。 */
    public static LlmMessage tool(String content) {
        return new LlmMessage("tool", content, null, null, null);
    }

    /** tool 消息（工具结果反馈），带 tool_call_id 和 name。 */
    public static LlmMessage toolWithId(String toolCallId, String name, String content) {
        return new LlmMessage("tool", content, null, toolCallId, name);
    }

    /** 是否有 API 原生 tool_calls。 */
    public boolean hasToolCalls() {
        return toolCalls != null && !toolCalls.isEmpty();
    }

    /** 从 Cache 持久化的 Map 恢复 LlmMessage（兼容 OpenAI 消息格式）。 */
    @SuppressWarnings("unchecked")
    public static LlmMessage fromCacheMap(java.util.Map<String, Object> map) {
        String role = (String) map.getOrDefault("role", "user");
        String content = (String) map.get("content");
        String toolCallId = (String) map.get("tool_call_id");
        String name = (String) map.get("name");

        java.util.List<LlmToolCall> toolCalls = null;
        Object tcObj = map.get("tool_calls");
        if (tcObj instanceof java.util.List<?> tcList && !tcList.isEmpty()) {
            toolCalls = new java.util.ArrayList<>();
            for (Object item : tcList) {
                if (item instanceof java.util.Map<?, ?> tcMap) {
                    String id = (String) ((java.util.Map<String, Object>) tcMap).get("id");
                    java.util.Map<String, Object> fn = (java.util.Map<String, Object>)
                            ((java.util.Map<String, Object>) tcMap).get("function");
                    if (fn != null) {
                        String tcName = (String) fn.get("name");
                        String argsJson = (String) fn.get("arguments");
                        java.util.Map<String, String> args = parseArgsJson(argsJson);
                        toolCalls.add(new LlmToolCall(
                                id != null ? id : "call_0",
                                tcName != null ? tcName : "unknown",
                                args));
                    }
                }
            }
        }

        return new LlmMessage(role, content, toolCalls, toolCallId, name);
    }

    @SuppressWarnings("unchecked")
    private static java.util.Map<String, String> parseArgsJson(String json) {
        if (json == null || json.isBlank()) return java.util.Map.of();
        try {
            java.util.Map<String, Object> raw = new com.fasterxml.jackson.databind.ObjectMapper()
                    .readValue(json, java.util.Map.class);
            java.util.Map<String, String> result = new java.util.LinkedHashMap<>();
            for (var entry : raw.entrySet()) {
                Object v = entry.getValue();
                result.put(entry.getKey(), v != null ? v.toString() : "");
            }
            return result;
        } catch (Exception e) {
            return java.util.Map.of();
        }
    }

    /** 转换为 Cache 持久化用的 Map（兼容 OpenAI 消息格式）。 */
    public java.util.Map<String, Object> toCacheMap() {
        java.util.Map<String, Object> m = new java.util.LinkedHashMap<>();
        m.put("role", role);
        if (content != null) {
            m.put("content", content);
        }
        if (hasToolCalls()) {
            java.util.List<java.util.Map<String, Object>> tcList = new java.util.ArrayList<>();
            for (LlmToolCall tc : toolCalls) {
                java.util.Map<String, Object> tcMap = new java.util.LinkedHashMap<>();
                tcMap.put("id", tc.id());
                tcMap.put("type", "function");
                java.util.Map<String, Object> fn = new java.util.LinkedHashMap<>();
                fn.put("name", tc.name());
                fn.put("arguments", tc.argsJson());
                tcMap.put("function", fn);
                tcList.add(tcMap);
            }
            m.put("tool_calls", tcList);
        }
        if (toolCallId != null) {
            m.put("tool_call_id", toolCallId);
        }
        if (name != null) {
            m.put("name", name);
        }
        return m;
    }
}
