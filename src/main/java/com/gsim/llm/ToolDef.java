package com.gsim.llm;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 工具定义，序列化到 OpenAI-compatible API 请求中的 {@code tools[]}。
 *
 * @param name        工具名称
 * @param description 工具描述（供模型选择工具时参考）
 * @param parameters  JSON Schema 参数定义。若为 null，序列化时 fallback 到宽 schema。
 */
public record ToolDef(String name, String description, Map<String, Object> parameters) {

    /** 向后兼容：使用默认宽 schema。 */
    public ToolDef(String name, String description) {
        this(name, description, defaultOpenSchema());
    }

    /** 默认宽 schema — 无约束，用于未声明 schema 的通用工具。 */
    public static Map<String, Object> defaultOpenSchema() {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("type", "object");
        params.put("properties", Map.of());
        params.put("additionalProperties", true);
        return params;
    }

    /** 构建严格 schema 的快捷方法。 */
    public static Map<String, Object> strictSchema(
            Map<String, Map<String, Object>> properties,
            java.util.List<String> required) {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("type", "object");
        params.put("properties", properties != null ? properties : Map.of());
        params.put("required", required != null ? required : java.util.List.of());
        params.put("additionalProperties", false);
        return params;
    }
}
