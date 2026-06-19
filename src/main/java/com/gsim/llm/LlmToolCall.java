package com.gsim.llm;

import java.util.Map;

/**
 * LLM API 返回的 tool call，来自 {@code message.tool_calls[]}。
 *
 * @param id        工具调用 ID（来自 API 的 {@code tool_call.id}）
 * @param name      工具名称（来自 API 的 {@code function.name}）
 * @param arguments 参数（来自 API 的 {@code function.arguments} JSON 字符串，已解析为 Map）
 */
public record LlmToolCall(String id, String name, Map<String, String> arguments) {
}
