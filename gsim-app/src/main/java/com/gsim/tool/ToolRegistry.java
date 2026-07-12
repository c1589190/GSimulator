package com.gsim.tool;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * 工具注册中心 — 管理所有 AgentTool 的注册和查找。
 */
public class ToolRegistry {

    private final Map<String, AgentTool> tools = new LinkedHashMap<>();

    /** 注册工具。同名工具后注册的覆盖先注册的。 */
    public void register(AgentTool tool) {
        tools.put(tool.name(), tool);
    }

    /** 按名称查找工具，不存在返回 null。 */
    public AgentTool get(String name) {
        return tools.get(name);
    }

    /** 检查是否已注册指定名称的工具。 */
    public boolean has(String name) {
        return tools.containsKey(name);
    }

    /** 仅在尚未注册时才注册工具。返回 true 表示实际注册了。 */
    public boolean registerIfAbsent(AgentTool tool) {
        if (tools.containsKey(tool.name())) {
            return false;
        }
        tools.put(tool.name(), tool);
        return true;
    }

    /** 返回所有已注册工具的名称。 */
    public Set<String> names() {
        return Collections.unmodifiableSet(tools.keySet());
    }

    /** 返回所有已注册的工具。 */
    public Map<String, AgentTool> all() {
        return Collections.unmodifiableMap(tools);
    }

    /** 按名称调用工具。 */
    public ToolResult call(ToolCall call) {
        AgentTool tool = tools.get(call.toolName());
        if (tool == null) {
            return ToolResult.fail(call.toolName(), "Unknown tool: " + call.toolName());
        }
        try {
            return tool.execute(call);
        } catch (Exception e) {
            return ToolResult.fail(call.toolName(), e.getMessage());
        }
    }
}
