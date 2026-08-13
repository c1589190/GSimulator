package com.gsim.agentlib.tool;

import java.util.List;

/**
 * 工具执行结果。
 */
public record ToolResult(boolean success, String toolName, List<Item> items, String error) {
    /**
     * 创建工具执行结果。
     *
     * @param success  是否成功
     * @param toolName 工具名称
     * @param items    返回的结果条目
     * @param error    错误信息（success 为 false 时使用）
     */
    public ToolResult {
        items = items != null ? List.copyOf(items) : List.of();
    }

    /**
     * 返回结果条目的不可修改视图（防御性拷贝，内部状态不受调用方修改影响）。
     *
     * @return 结果条目列表（不可修改）
     */
    public List<Item> items() {
        return List.copyOf(items);
    }

    /**
     * 创建成功结果。
     *
     * @param toolName 工具名称
     * @param items    结果条目列表
     * @return 成功状态的 ToolResult
     */
    public static ToolResult ok(String toolName, List<Item> items) {
        return new ToolResult(true, toolName, items, "");
    }

    /**
     * 创建失败结果。
     *
     * @param toolName 工具名称
     * @param error    错误描述
     * @return 失败状态的 ToolResult
     */
    public static ToolResult fail(String toolName, String error) {
        return new ToolResult(false, toolName, List.of(), error);
    }

    /** 单条搜索结果。 */
    public record Item(String title, String path, String snippet, double score) {}
}
