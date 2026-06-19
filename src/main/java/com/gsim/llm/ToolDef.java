package com.gsim.llm;

/**
 * 工具定义，序列化到 OpenAI-compatible API 请求中的 {@code tools[]}。
 *
 * @param name        工具名称
 * @param description 工具描述（供模型选择工具时参考）
 */
public record ToolDef(String name, String description) {
}
