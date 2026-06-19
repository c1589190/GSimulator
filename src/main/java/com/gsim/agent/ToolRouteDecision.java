package com.gsim.agent;

/**
 * 工具路由决策结果 — 当前轮允许的工具集合。
 *
 * @param allToolsAllowed 当为 true 时，不限制工具名（由确认机制兜底安全），
 *                        allowedTools 中仍列出推荐工具供 debug
 */
public record ToolRouteDecision(
        java.util.Set<String> allowedTools,
        String routeName,
        String reason,
        boolean allToolsAllowed) {

    /** 兼容构造：allToolsAllowed=false */
    public ToolRouteDecision(java.util.Set<String> allowedTools, String routeName, String reason) {
        this(allowedTools, routeName, reason, false);
    }

    /** 创建通配路由（所有工具都放行，由确认机制兜底）。 */
    public static ToolRouteDecision wildcard(
            java.util.Set<String> recommendedTools, String routeName, String reason) {
        return new ToolRouteDecision(
                java.util.Collections.unmodifiableSet(recommendedTools),
                routeName, reason, true);
    }
}
