package com.gsim.agent;

import com.gsim.agentlib.tool.AgentTool;

/**
 * 工具过滤评估器 — 根据 {@link ToolFilterConfig} 和 {@link AgentTool#permission()} 判断指定工具是否允许使用。
 *
 * <p>支持四种过滤模式：
 * <ul>
 *   <li>"all" — 所有工具均允许</li>
 *   <li>"read_only" — 仅允许 READ permission 的工具</li>
 *   <li>"none" — 仅允许 finish_action</li>
 *   <li>"custom" — 按 allow/deny 列表精确控制</li>
 * </ul>
 *
 * <p>此外，当 AgentConfig 设置了 {@code maxPermission} 和 {@code allowList}（用于 SubAgent 权限控制），
 * 过滤时也会检查工具的 permission 等级是否超出 maxPermission。
 */
public class ToolFilterEvaluator {

    /**
     * 判断指定工具是否允许在当前配置下使用（仅基于 ToolFilterConfig mode）。
     * 不涉及工具实例，只做名称匹配。
     *
     * @param config   工具过滤配置（mode 及 allow/deny 列表）
     * @param toolName 待检查的工具名称
     * @return true 表示允许使用，false 表示被过滤
     */
    public static boolean allows(ToolFilterConfig config, String toolName) {
        return allowsByMode(config.mode(), config.allow(), config.deny(), toolName);
    }

    private static boolean allowsByMode(
            String mode, java.util.List<String> allow, java.util.List<String> deny, String toolName) {
        return switch (mode) {
            case "all" -> true;
            case "read_only" -> {
                // With ToolRegistry access, use permission(); fallback to old static map
                yield ToolCategoryRegistry.isReadOnly(toolName) || ToolCategoryRegistry.isControl(toolName);
            }
            case "none" -> "finish_action".equals(toolName);
            case "custom" -> {
                if (deny.contains(toolName)) yield false;
                if (allow.isEmpty()) yield true;
                yield allow.contains(toolName);
            }
            default -> false;
        };
    }

    /**
     * 判断指定工具是否允许在当前配置下使用（含 permission 等级过滤）。
     *
     * <p>相比 {@link #allows(ToolFilterConfig, String)}，此方法额外检查工具的
     * {@link AgentTool#permission()} 是否超出 {@code maxPermission}。
     *
     * @param config        工具过滤配置
     * @param toolName      工具名称
     * @param toolPermission 工具的 permission 等级
     * @param maxPermission SubAgent 的最大允许权限（null = 不限制）
     * @param allowList     白名单（null/empty = 不启用），白名单工具始终放行
     * @return true 表示允许使用
     */
    public static boolean allowsWithPermission(
            ToolFilterConfig config,
            String toolName,
            AgentTool.Permission toolPermission,
            AgentTool.Permission maxPermission,
            java.util.List<String> allowList) {

        // SELF 工具始终放行（Agent 自身流程控制，权限等级最低）
        if (toolPermission == AgentTool.Permission.SELF) {
            return true;
        }

        // allowList 白名单工具始终放行（不管 permission 等级）
        if (allowList != null && allowList.contains(toolName)) {
            return true;
        }

        // filter config mode 放行检查
        if (!allows(config, toolName)) {
            return false;
        }

        // maxPermission 门禁：SELF < READ < WRITE < SYSTEM
        if (maxPermission != null) {
            return toolPermission.ordinal() <= maxPermission.ordinal();
        }

        return true;
    }
}
