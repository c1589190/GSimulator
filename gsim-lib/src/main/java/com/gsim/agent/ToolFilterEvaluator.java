package com.gsim.agent;

/**
 * 工具过滤评估器 — 根据 {@link ToolFilterConfig} 判断指定工具是否允许使用。
 *
 * <p>支持四种过滤模式：
 * <ul>
 *   <li>"all" — 所有工具均允许</li>
 *   <li>"read_only" — 仅允许只读工具（READ_ONLY + CONTROL）</li>
 *   <li>"none" — 仅允许 finish_action</li>
 *   <li>"custom" — 按 allow/deny 列表精确控制</li>
 * </ul>
 */
public class ToolFilterEvaluator {

    /**
     * 判断指定工具是否允许在当前配置下使用。
     *
     * @param config   工具过滤配置（mode 及 allow/deny 列表）
     * @param toolName 待检查的工具名称
     * @return true 表示允许使用，false 表示被过滤
     */
    public static boolean allows(ToolFilterConfig config, String toolName) {
        return switch (config.mode()) {
            case "all" -> true;
            case "read_only" -> ToolCategoryRegistry.isReadOnly(toolName) || ToolCategoryRegistry.isControl(toolName);
            case "none" -> "finish_action".equals(toolName);
            case "custom" -> {
                if (config.deny().contains(toolName)) yield false;
                if (config.allow().isEmpty()) yield true;
                yield config.allow().contains(toolName);
            }
            default -> false;
        };
    }
}
