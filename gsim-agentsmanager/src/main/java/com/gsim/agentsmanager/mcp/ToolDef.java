package com.gsim.agentsmanager.mcp;

/**
 * MCP 工具定义的不可变值对象。
 *
 * <p>包含工具名称、人类可读描述和 JSON Schema。
 * 被 {@link McpToolRegistry} 和 {@link AbstractMcpServer} 使用。
 *
 * @param name        工具名称（如 {@code gsim_list_worlds}）
 * @param description 人类可读的工具描述
 * @param schema      JSON Schema 字符串，定义工具的参数结构
 */
public record ToolDef(String name, String description, String schema) {}
