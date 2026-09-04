package com.gsim.agentsmanager.mcp;

import com.gsim.agentsmanager.tool.ToolResult;

/**
 * 工具结果溢出处理器 — MCP 响应超过 {@link McpResponseConfig#maxJsonBytes()} 时被调用。
 *
 * <p>实现方可将超限内容改写（例如暂存为文档并返回 docId 占位 snippet），
 * 或返回 {@code null} 表示"不处理"，由适配器回退到内置截断路径。
 *
 * <p>本接口只引用 {@code com.gsim.agentsmanager} 类型，保持模块零业务依赖。
 */
@FunctionalInterface
public interface ToolResultOverflowHandler {

    /**
     * 处理超限的工具结果。
     *
     * @param result   原始工具结果（未被分页切片）
     * @param toolName MCP 面工具名（含 {@code gsim_} 前缀）
     * @return 改写后的结果；返回 {@code null} 表示不处理（适配器回退到截断）
     */
    ToolResult handle(ToolResult result, String toolName);
}
