package com.gsim.tool;

import java.util.Collections;
import java.util.List;

/**
 * 工具执行结果。
 */
public record ToolResult(
        boolean success,
        String toolName,
        List<Item> items,
        String error
) {
    public ToolResult {
        items = items != null ? Collections.unmodifiableList(items) : List.of();
    }

    public static ToolResult ok(String toolName, List<Item> items) {
        return new ToolResult(true, toolName, items, "");
    }

    public static ToolResult fail(String toolName, String error) {
        return new ToolResult(false, toolName, List.of(), error);
    }

    /** 单条搜索结果。 */
    public record Item(
            String title,
            String path,
            String snippet,
            double score
    ) {}
}
