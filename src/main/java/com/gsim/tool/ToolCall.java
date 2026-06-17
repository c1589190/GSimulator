package com.gsim.tool;

import java.util.Collections;
import java.util.Map;

/**
 * 工具调用请求 — 包含工具名和参数。
 */
public record ToolCall(
        String toolName,
        Map<String, String> parameters
) {
    public ToolCall {
        parameters = parameters != null
                ? Collections.unmodifiableMap(parameters)
                : Collections.emptyMap();
    }

    /** 获取单个参数值，不存在返回 null。 */
    public String param(String key) {
        return parameters.get(key);
    }

    /** 获取单个参数值，不存在返回默认值。 */
    public String param(String key, String defaultValue) {
        return parameters.getOrDefault(key, defaultValue);
    }
}
