package com.gsim.agent.core;

import com.gsim.agent.ToolCategory;
import com.gsim.agent.ToolCategoryRegistry;

import java.util.List;
import java.util.Set;

/**
 * 工具过滤规则 — 控制 Agent 可用工具集。
 *
 * <p>mode:
 * <ul>
 *   <li>"all" — 全部工具可用（主 Agent）</li>
 *   <li>"read_only" — 仅 READ_ONLY + CONTROL（SubAgent 默认）</li>
 *   <li>"custom" — 按 allow/deny 列表过滤</li>
 * </ul>
 */
public record ToolFilterConfig(
        String mode,
        List<String> allow,
        List<String> deny
) {
    public static final ToolFilterConfig ALL = new ToolFilterConfig("all", List.of(), List.of());
    public static final ToolFilterConfig READ_ONLY = new ToolFilterConfig("read_only", List.of(), List.of());

    /** 判断工具是否可用。 */
    public boolean allows(String toolName) {
        return switch (mode) {
            case "all" -> true;
            case "read_only" -> ToolCategoryRegistry.isReadOnly(toolName)
                    || ToolCategoryRegistry.isControl(toolName);
            case "custom" -> {
                if (deny.contains(toolName)) yield false;
                if (allow.isEmpty()) yield true;
                yield allow.contains(toolName);
            }
            default -> false;
        };
    }
}
