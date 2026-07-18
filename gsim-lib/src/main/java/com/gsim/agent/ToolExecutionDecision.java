package com.gsim.agent;

/**
 * 工具执行决策 — 由执行前门禁返回。
 */
public record ToolExecutionDecision(
        ToolExecutionDecisionType decision, String reason, ToolCategory category, boolean allowedByRoute) {

    /**
     * 创建"允许执行"决策。
     *
     * @param reason         允许的原因说明
     * @param cat            工具分类
     * @param allowedByRoute 是否已通过路由检查
     * @return 允许执行的决策实例
     */
    public static ToolExecutionDecision allow(String reason, ToolCategory cat, boolean allowedByRoute) {
        return new ToolExecutionDecision(ToolExecutionDecisionType.ALLOW, reason, cat, allowedByRoute);
    }

    /**
     * 创建"需用户确认"决策。
     *
     * @param reason         需要确认的原因说明
     * @param cat            工具分类
     * @param allowedByRoute 是否已通过路由检查
     * @return 需要确认的决策实例
     */
    public static ToolExecutionDecision needConfirmation(String reason, ToolCategory cat, boolean allowedByRoute) {
        return new ToolExecutionDecision(ToolExecutionDecisionType.NEED_CONFIRMATION, reason, cat, allowedByRoute);
    }

    /**
     * 创建"拒绝执行"决策。
     *
     * @param reason         拒绝的原因说明
     * @param cat            工具分类
     * @param allowedByRoute 是否已通过路由检查
     * @return 拒绝执行的决策实例
     */
    public static ToolExecutionDecision reject(String reason, ToolCategory cat, boolean allowedByRoute) {
        return new ToolExecutionDecision(ToolExecutionDecisionType.REJECT, reason, cat, allowedByRoute);
    }
}
