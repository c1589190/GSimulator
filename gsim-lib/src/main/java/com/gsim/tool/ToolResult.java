package com.gsim.tool;

import java.util.Collections;
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
        items = items != null ? Collections.unmodifiableList(items) : List.of();
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
