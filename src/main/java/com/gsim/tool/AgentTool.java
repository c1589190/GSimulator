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

    /**
     * 工具的 JSON Schema 参数定义。
     * 返回 null 表示无严格 schema（序列化时使用宽 schema）。
     */
    default java.util.Map<String, Object> getParameters() {
        return null;
    }
}
