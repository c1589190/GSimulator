package com.gsim.tool;

import java.util.Collections;
import java.util.Map;

/**
 * 工具调用请求 — 包含工具名和参数。
 */
public record ToolCall(String toolName, Map<String, String> parameters) {
    /**
     * 创建工具调用请求。
     *
     * @param toolName   工具名称
     * @param parameters 参数映射，为 null 时视为空映射
     */
    public ToolCall {
        parameters = parameters != null ? Collections.unmodifiableMap(parameters) : Collections.emptyMap();
    }

    /**
     * 获取单个参数值。
     *
     * @param key 参数名
     * @return 参数值，不存在时返回 null
     */
    public String param(String key) {
        return parameters.get(key);
    }

    /**
     * 获取单个参数值，不存在返回默认值。
     *
     * @param key          参数名
     * @param defaultValue 默认值
     * @return 参数值，不存在时返回 defaultValue
     */
    public String param(String key, String defaultValue) {
        return parameters.getOrDefault(key, defaultValue);
    }
}
