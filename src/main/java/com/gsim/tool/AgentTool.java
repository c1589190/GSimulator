package com.gsim.tool;

/**
 * Agent 可调用工具的抽象接口。
 * 所有工具必须实现此接口并通过 ToolRegistry 注册。
 */
public interface AgentTool {

    /** 工具名称（用于注册和调用）。 */
    String name();

    /** 工具描述（供 LLM 选择工具时参考）。 */
    String description();

    /** 执行工具并返回结果。 */
    ToolResult execute(ToolCall call);
}
