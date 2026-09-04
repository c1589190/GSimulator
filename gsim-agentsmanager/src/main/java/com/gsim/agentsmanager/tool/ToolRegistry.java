package com.gsim.agentsmanager.tool;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * 工具注册中心 — 管理所有 AgentTool 的注册和查找。
 */
public class ToolRegistry {

    private final Map<String, AgentTool> tools = new LinkedHashMap<>();

    /**
     * 注册工具。同名工具后注册的覆盖先注册的。
     *
     * @param tool 要注册的工具实例
     */
    public void register(AgentTool tool) {
        tools.put(tool.name(), tool);
    }

    /**
     * 按名称查找工具。
     *
     * @param name 工具名称
     * @return 已注册的工具，不存在时返回 null
     */
    public AgentTool get(String name) {
        return tools.get(name);
    }

    /**
     * 检查是否已注册指定名称的工具。
     *
     * @param name 工具名称
     * @return true 表示该名称已注册
     */
    public boolean has(String name) {
        return tools.containsKey(name);
    }

    /**
     * 仅在尚未注册时才注册工具。
     *
     * @param tool 要注册的工具实例
     * @return true 表示实际注册了；false 表示该名称已存在
     */
    public boolean registerIfAbsent(AgentTool tool) {
        if (tools.containsKey(tool.name())) {
            return false;
        }
        tools.put(tool.name(), tool);
        return true;
    }

    /**
     * 返回所有已注册工具的名称。
     *
     * @return 不可修改的工具名称集合
     */
    public Set<String> names() {
        return Collections.unmodifiableSet(tools.keySet());
    }

    /**
     * 返回所有已注册的工具。
     *
     * @return 工具名称到工具实例的不可修改映射
     */
    public Map<String, AgentTool> all() {
        return Collections.unmodifiableMap(tools);
    }

    /**
     * 按名称调用工具。
     *
     * @param call 包含工具名和参数的调用请求
     * @return 工具执行结果，工具不存在时返回 fail 结果
     */
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
