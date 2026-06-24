package com.gsim.agent;

import java.util.Map;

/**
 * 解析出的工具调用 — 从 LLM 响应文本中提取。
 * 由 {@link ToolCallExtractor} 生成，供 OrchestratorAgent 和 SubAgent 共用。
 */
public record ParsedToolCall(String tool, Map<String, String> args) {
}
